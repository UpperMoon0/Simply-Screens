import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import re
import zipfile
from unittest.mock import patch
import ci_native_artifact as native
import visual_test


class NativeArtifactTest(unittest.TestCase):
    def test_toolchain_cli_creates_its_parent_on_a_fresh_runner(self):
        with tempfile.TemporaryDirectory() as d, patch.object(native,'toolchain',return_value={'java':'fixture'}):
            output=Path(d)/'build/packet-toolchain.json'
            self.assertFalse(output.parent.exists())
            with patch('sys.argv',['ci_native_artifact.py','toolchain','--toolchain-file',str(output)]):
                native.main()
            self.assertEqual({'java':'fixture'},json.loads(output.read_text()))
            self.assertFalse(output.with_suffix('.tmp').exists())

    def test_wrong_target_source_toolchain_and_poisoned_jar_fail_closed(self):
        with tempfile.TemporaryDirectory() as d, patch.object(native,'verify_native_metadata'), patch.object(native,'verify_compiler'):
            package=Path(d);jar=package/'packetweave-fabric-1.20.1-0.1.1.jar';jar.write_bytes(b'fixture')
            identity=dict(head='h',source_sha256='s',packetweave_source='p');compiler={'java':'25'};build={'java':'21'}
            receipt=dict(schema=1,status='pass',target='fabric-1.20.1',identity=identity,
                         packet_launcher=compiler,build_launcher=build,jar=jar.name,sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
            validate=lambda r:native.validate(r,identity,'fabric-1.20.1',compiler,build,package)
            self.assertEqual(jar,validate(receipt))
            for mutate in (lambda r:r.update(status='fail'),lambda r:r.update(target='forge-1.20.1'),
                           lambda r:r['identity'].update(head='old'),lambda r:r['identity'].update(source_sha256='old'),
                           lambda r:r['identity'].update(packetweave_source='old'),lambda r:r.update(packet_launcher={}),
                           lambda r:r.update(build_launcher={}),lambda r:r.update(jar='../escape.jar'),
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

    def test_resolved_compiler_is_not_confused_with_launcher_or_wrong_bytecode(self):
        with tempfile.TemporaryDirectory() as d:
            jar=Path(d)/'native.jar'
            report=dict(schema=1,task=':compileJava',execution='executed',release=17,
                        compiler=dict(language_version=17,java_runtime_version='17.0.20+1',jvm_version='17.0.20+1',vendor='Eclipse Adoptium'))
            def write(major):
                with zipfile.ZipFile(jar,'w') as archive:
                    archive.writestr('com/nstut/packetweave/api/TransferRegistry.class',bytes.fromhex('cafebabe0000')+major.to_bytes(2,'big'))
            write(61);native.verify_compiler(report,'fabric-1.20.1',jar)
            for mutate in (lambda r:r['compiler'].update(language_version=25),lambda r:r.update(release=21),
                           lambda r:r.update(execution='from-cache'),lambda r:r.update(task=':compileTestJava'),
                           lambda r:r['compiler'].pop('vendor')):
                changed=copy.deepcopy(report);mutate(changed)
                with self.assertRaises(ValueError):native.verify_compiler(changed,'fabric-1.20.1',jar)
            write(65)
            with self.assertRaisesRegex(ValueError,'bytecode'):native.verify_compiler(report,'fabric-1.20.1',jar)

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

    def test_shader_jobs_share_published_exact_toolchains_including_openui_and_runtime_restore(self):
        workflow=(native.ROOT/'.github/workflows/shader-colors.yml').read_text()
        prepare=workflow.split('  prepare:\n',1)[1].split('  native-build:\n',1)[0]
        producer=workflow.split('  native-build:\n',1)[1].split('  shader:\n',1)[0]
        consumer=workflow.split('  shader:\n',1)[1].split('  required:\n',1)[0]
        for major in (17,21,25):
            self.assertIn(f'java{major}: ${{{{ steps.java{major}.outputs.version }}}}',prepare)
            setup=prepare.split(f'id: java{major}\n',1)[1].split('- name:',1)[0]
            self.assertIn(f"java-version: '{major}'",setup)
            self.assertIn('check-latest: true',setup)
            self.assertIn('distribution: temurin',setup)
        for job in (producer,consumer):
            versions=re.findall(r'^\s+java-version: (.+)$',job,re.MULTILINE)
            self.assertEqual([
                "${{ matrix.target == 'forge-1.20.1' && needs.prepare.outputs.java17 || needs.prepare.outputs.java25 }}",
                '${{ needs.prepare.outputs.java21 }}',  # OpenUI must not reset to a cached major-only JDK.
                '${{ needs.prepare.outputs.java17 }}',  # Fabric 1.20 runtime installation.
                '${{ needs.prepare.outputs.java21 }}',  # Restore the same build launcher afterwards.
            ],versions)
        action=(native.ROOT/'.github/actions/openui/action.yml').read_text()
        self.assertIn("default: '21'",action)  # Unrelated callers retain their existing major selection.
        self.assertIn('java-version: ${{ inputs.java-version }}',action)
        self.assertIn('jdk-${{ inputs.java-version }}-',action)

    def test_exact_patch_drift_is_still_rejected_before_native_artifact_use(self):
        identity=dict(head='current',source_sha256='source',packetweave_source='pinned')
        packet={'java':'openjdk version "25.0.5" Temurin-25.0.5+8','system':'Linux','architecture':'x86_64'}
        build={'java':'openjdk version "21.0.13" Temurin-21.0.13+9','system':'Linux','architecture':'x86_64'}
        receipt=dict(schema=1,status='pass',target='fabric-1.20.1',identity=identity,
                     packet_launcher=packet,build_launcher=build)
        for field in ('packet_launcher','build_launcher'):
            changed=copy.deepcopy(receipt)
            changed[field]['java']=changed[field]['java'].replace('+8','+1').replace('+9','+1')
            with self.assertRaisesRegex(ValueError,'toolchain mismatch'):
                native.validate(changed,identity,'fabric-1.20.1',packet,build,Path('unused'))

if __name__=='__main__':unittest.main()
