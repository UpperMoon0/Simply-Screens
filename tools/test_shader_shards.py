"""Coverage equivalence, shard aggregation, and cache-integrity regression checks."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, MagicMock

import shader_test as shader
import shader_stack


class ShaderShardsTest(unittest.TestCase):
    def test_frozen_pre_optimization_manifest_is_identical(self):
        baseline=json.loads((shader.ROOT/'tools/shader/coverage-manifest.json').read_text())
        self.assertEqual(baseline,shader.coverage_manifest())
        flattened=[v for variants in shader.SHARDS.values() for v in variants]
        self.assertEqual(list(shader.VARIANTS),flattened)
        self.assertEqual(len(flattened),len(set(flattened)))
        self.assertEqual(set(shader.PAIRED_VARIANTS),set(shader.SHARDS['paired']))
        self.assertEqual(92,sum(len(v['scenarios']) for v in baseline.values()))

    def fixtures(self,root):
        receipts=[]
        for target in shader.TARGETS:
            for shard,variants in shader.SHARDS.items():
                path=root/target/shard
                path.mkdir(parents=True)
                graphics=dict(graphicsVendor='vendor',graphicsRenderer='renderer',graphicsVersion='version',
                              runtimeJavaVersion='21',runtimeJavaVendor='Temurin',runtimeArchitecture='amd64')
                for v in variants:
                    directory=path/v;directory.mkdir()
                    (directory/'graphics.json').write_text(json.dumps(graphics))
                    (directory/'measurements.json').write_text('{}')
                receipt=dict(schema=2,target=target,shard=shard,status='pass',dirty=False,head='head',source_sha256='a'*64,
                    variants={v:'pass' for v in variants},coverage={v:shader.coverage_manifest()[v] for v in variants},
                    pack_options={v:shader.pack_options(v) for v in variants},
                    stack=shader.load_lock()['stacks'][target],shaderpack=shader.load_lock()['shaderpack'],
                    evidence_sha256={},graphics=graphics,repeat_stability={},glow_evidence={},
                    alpha_delta_p95={v:[0]*4 for v in shader.ON_REPEATS})
                file=path/'result.json';file.write_text(json.dumps(receipt));receipts.append((file,receipt))
        return receipts

    def test_gate_preserves_complete_partition_and_exact_source_identity(self):
        # This isolates aggregation. Pixel, settling, original-control, alpha and glow
        # oracles keep their separate real-fixture regression suites unchanged.
        with tempfile.TemporaryDirectory() as temporary, \
             patch.object(shader,'evidence_files',return_value=[]), \
             patch.object(shader,'validate_variant'), \
             patch.object(shader,'validate_stability',return_value=.1), \
             patch.object(shader,'validate_glow'):
            root=Path(temporary);receipts=self.fixtures(root)
            shader.verify_receipts(root,'head')
            file,receipt=receipts[0]
            mutations=[lambda r:r.update(head='stale'),lambda r:r.update(dirty=True),
                lambda r:r.update(schema=1),lambda r:r.update(source_sha256='b'*64),
                lambda r:r['variants'].pop('fixed'),lambda r:r['coverage']['fixed']['scenarios'].pop(),
                lambda r:r['pack_options']['fixed'].update(BLOOM_ENABLED='-1'),
                lambda r:r.update(shard='paired'),lambda r:r.update(status='fail'),
                lambda r:r.update(evidence_sha256={'forged.png':'abc'})]
            for mutate in mutations:
                bad=copy.deepcopy(receipt);mutate(bad);file.write_text(json.dumps(bad))
                with self.assertRaises(ValueError):shader.verify_receipts(root,'head')
            file.write_text(json.dumps(receipt))
            duplicate=root/'duplicate';duplicate.mkdir();(duplicate/'result.json').write_text(json.dumps(receipt))
            with self.assertRaisesRegex(ValueError,'duplicate'):shader.verify_receipts(root,'head')
            (duplicate/'result.json').unlink();file.unlink()
            with self.assertRaisesRegex(ValueError,'missing'):shader.verify_receipts(root,'head')

    def test_cross_shard_backend_and_actual_runtime_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary, \
             patch.object(shader,'evidence_files',return_value=[]), \
             patch.object(shader,'validate_variant'), \
             patch.object(shader,'validate_stability',return_value=.1), \
             patch.object(shader,'validate_glow'):
            root=Path(temporary);receipts=self.fixtures(root)
            file,receipt=receipts[0]
            for field in ('graphicsRenderer','runtimeJavaVersion','runtimeJavaVendor','runtimeArchitecture'):
                changed=copy.deepcopy(receipt);changed['graphics'][field]='different'
                file.write_text(json.dumps(changed))
                for variant in shader.SHARDS['temporal']:
                    (file.parent/variant/'graphics.json').write_text(json.dumps(changed['graphics']))
                with self.assertRaisesRegex(ValueError,'between shader shards'):shader.verify_receipts(root,'head')

    def test_cold_download_is_verified_before_cache_population(self):
        data=b'reviewed shader bytes';artifact=dict(filename='fixture.jar',sha512=hashlib.sha512(data).hexdigest(),url='https://cdn.modrinth.com/fixture')
        response=MagicMock();response.__enter__.return_value.read.return_value=data
        with tempfile.TemporaryDirectory() as temporary, patch.object(shader_stack.urllib.request,'urlopen',return_value=response) as fetch:
            cache=Path(temporary)/'new-cache'
            self.assertEqual(data,shader_stack.download(artifact,cache).read_bytes());fetch.assert_called_once()
            self.assertFalse((cache/'fixture.jar.tmp').exists())

    def test_checksum_verified_warm_cache_does_not_download(self):
        data=b'reviewed shader bytes';artifact=dict(filename='fixture.jar',sha512=hashlib.sha512(data).hexdigest(),url='https://cdn.modrinth.com/fixture')
        with tempfile.TemporaryDirectory() as temporary, patch.object(shader_stack.urllib.request,'urlopen') as fetch:
            cache=Path(temporary);(cache/'fixture.jar').write_bytes(data)
            self.assertEqual(data,shader_stack.download(artifact,cache).read_bytes());fetch.assert_not_called()

    def test_poisoned_cache_is_replaced_only_by_checksum_matching_download(self):
        data=b'reviewed shader bytes';artifact=dict(filename='fixture.jar',sha512=hashlib.sha512(data).hexdigest(),url='https://cdn.modrinth.com/fixture')
        response=MagicMock();response.__enter__.return_value.read.return_value=data
        with tempfile.TemporaryDirectory() as temporary, patch.object(shader_stack.urllib.request,'urlopen',return_value=response) as fetch:
            cache=Path(temporary);(cache/'fixture.jar').write_bytes(b'poison')
            self.assertEqual(data,shader_stack.download(artifact,cache).read_bytes());fetch.assert_called_once()
            (cache/'fixture.jar').write_bytes(b'poison');response.__enter__.return_value.read.return_value=b'also poison'
            with self.assertRaisesRegex(ValueError,'checksum mismatch'):shader_stack.download(artifact,cache)

    def test_runtime_timeout_and_original_oracles_are_not_sharded_away(self):
        workflow=(shader.ROOT/'.github/workflows/shader-colors.yml').read_text()
        self.assertIn('timeout-minutes: 90',workflow)
        self.assertIn('max-parallel: 10',workflow)
        self.assertIn('--verify-receipts evidence --head "$EXPECTED_HEAD"',workflow)
        self.assertIn('--shard',workflow)
        self.assertIn('original',shader.SHARDS['temporal'])
        self.assertIn('original-static',shader.SHARDS['paired'])

if __name__=='__main__':unittest.main()
