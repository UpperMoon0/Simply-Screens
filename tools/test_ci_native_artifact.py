import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch
import ci_native_artifact as native
import visual_test


class NativeArtifactTest(unittest.TestCase):
    def test_wrong_target_source_toolchain_and_poisoned_jar_fail_closed(self):
        with tempfile.TemporaryDirectory() as d, patch.object(native,'verify_native_metadata'):
            package=Path(d);jar=package/'packetweave-fabric-1.20.1-0.1.1.jar';jar.write_bytes(b'fixture')
            identity=dict(head='h',source_sha256='s',packetweave_source='p');compiler={'java':'25'};build={'java':'21'}
            receipt=dict(schema=1,status='pass',target='fabric-1.20.1',identity=identity,
                         packet_toolchain=compiler,build_toolchain=build,jar=jar.name,sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
            validate=lambda r:native.validate(r,identity,'fabric-1.20.1',compiler,build,package)
            self.assertEqual(jar,validate(receipt))
            for mutate in (lambda r:r.update(status='fail'),lambda r:r.update(target='forge-1.20.1'),
                           lambda r:r['identity'].update(head='old'),lambda r:r['identity'].update(source_sha256='old'),
                           lambda r:r['identity'].update(packetweave_source='old'),lambda r:r.update(packet_toolchain={}),
                           lambda r:r.update(build_toolchain={}),lambda r:r.update(jar='../escape.jar'),
                           lambda r:r.update(sha256='poison')):
                changed=copy.deepcopy(receipt);mutate(changed)
                with self.assertRaises(ValueError):validate(changed)
            jar.write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'corrupted'):validate(receipt)
            jar.unlink()
            with self.assertRaisesRegex(ValueError,'missing'):validate(receipt)

    def test_actual_native_manifests_bind_every_loader_and_game_version(self):
        with tempfile.TemporaryDirectory() as d:
            jar=Path(d)/'fixture.jar'
            for target in native.TARGETS:
                project=native.ROOT/'vendor/PacketWeave/targets'/target
                version=native.property_value(project/'gradle.properties','mod_version')
                metadata=native.loader_metadata(target)
                text=(project/'src/main/resources'/metadata).read_text().replace('${mod_version}',version)
                def write(content):
                    with zipfile.ZipFile(jar,'w') as archive:
                        archive.writestr(metadata,content)
                        archive.writestr('com/nstut/packetweave/api/TransferRegistry.class',b'fixture')
                write(text);native.verify_native_metadata(jar,target)
                game=target.split('-',1)[1]
                write(text.replace(game,'0.0.0'))
                with self.assertRaisesRegex(ValueError,'target/loader'):native.verify_native_metadata(jar,target)

    def test_failed_production_check_invalidates_old_success(self):
        with tempfile.TemporaryDirectory() as d, patch.object(native,'identity',return_value={}), \
             patch.object(native,'verify_production_build',side_effect=RuntimeError('production failed')):
            package=Path(d);(package/'prerequisite.json').write_text('{"status":"pass"}')
            with self.assertRaisesRegex(RuntimeError,'production failed'):
                native.create(Path(d),'fabric-1.20.1',package,{})
            self.assertFalse((package/'prerequisite.json').exists())

    def test_missing_prerequisite_cannot_be_consumed(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(FileNotFoundError):native.consume(Path(d),'fabric-1.20.1',Path(d),{})

    def test_digest_only_snapshot_matches_copied_snapshot(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(visual_test.snapshot(native.ROOT),visual_test.snapshot(native.ROOT,Path(d)))

    def test_workflow_gate_requires_native_success_and_no_duplicate_production_build(self):
        workflow=(native.ROOT/'.github/workflows/shader-colors.yml').read_text()
        self.assertIn('needs: [prepare, native-build, shader]',workflow)
        self.assertIn('test "$NATIVE" = success',workflow)
        self.assertEqual(1,workflow.count('ci_native_artifact.py create'))
        self.assertEqual(1,workflow.count('ci_native_artifact.py consume'))
        self.assertEqual(1,workflow.count('./gradlew build --no-daemon'))

if __name__=='__main__':unittest.main()
