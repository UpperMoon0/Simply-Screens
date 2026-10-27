import copy
import json
from unittest.mock import patch
import shutil
from pathlib import Path
import tempfile
import unittest

import requested_visual as runner
import requested_visual_26 as adapter
import visual_test

ROOT=Path(__file__).resolve().parents[1]
class RequestedVisualTest(unittest.TestCase):
    def stage(self,path):
        for name in ('common-26.2','neoforge-26.1.2','tools/visual','tools/visual_controls','common/src/main/resources','fabric-26.2','neoforge-26.2'):
            shutil.copytree(ROOT/name,path/name,ignore=shutil.ignore_patterns('build','run','.gradle'))
    def test_standalone_commands_keep_native_paths_and_explicit_dependencies(self):
        result=runner.command(Path('/stage'),'fabric-26.2','classes',
            {'openui':Path('/deps/openui.jar'),'packetweave':Path('/deps/pw.jar')},Path('/deps/pw/core'))
        self.assertEqual('/stage/fabric-26.2/gradlew',result[0])
        self.assertIn('/stage/fabric-26.2',result)
        self.assertIn('-PopenuiJar=/deps/openui.jar',result)
        self.assertIn('-PpacketweaveSource=/deps/pw/core',result)
    def test_shared_driver_instrumentation_is_native_and_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            stage=Path(tmp);self.stage(stage);runner.prepare_snapshot(stage,'fabric-26.2')
            drivers=stage/adapter.MODULE/visual_test.PACKAGE/'testing/visual'
            client=(drivers/'VisualClient.java').read_text()
            self.assertIn('mc.gameRenderer.mainRenderTarget()',client)
            self.assertIn('RenderSystem.getDevice().getDeviceInfo()',client)
            self.assertNotIn('org.lwjgl.opengl',client)
            self.assertIn('waitingFor = "native terrain worker queue"',client)
            self.assertEqual(2,client.count('waitingFor = sectionReadiness(mc, pos)'))
            self.assertIn('CompiledSectionMesh.UNCOMPILED',client)
            self.assertIn('section.getVisibility(net.minecraft.util.Util.getMillis())',client)
            self.assertIn('mc.levelExtractor.allChanged()',client)
            self.assertNotIn('mc.levelRenderer.invalidateCompiledGeometry',client)
            self.assertNotIn('section.compile',client)
            self.assertNotIn('section.setFadeDuration',client)
            self.assertIn('if (!mc.levelRenderer.hasRenderedAllSections())',client)
            self.assertEqual(2,client.count('if (!mc.levelRenderer.isSectionCompiledAndVisible(pos))'))
            self.assertNotRegex(client,r'__[A-Z_]+__')
            self.assertIn("'--width','1920','--height','1080'",(stage/'fabric-26.2/build.gradle').read_text())
            with self.assertRaises(RuntimeError):runner.prepare_snapshot(stage,'fabric-26.2')
    def test_negative_controls_preserve_reversed_z_and_reset_between_variants(self):
        with tempfile.TemporaryDirectory() as tmp:
            stage=Path(tmp);self.stage(stage);runner.prepare_snapshot(stage,'fabric-26.2')
            originals={path:(stage/path).read_text() for path in adapter.mutable_paths()}
            target=stage/adapter.mutable_paths()[0]
            renderer=stage/adapter.MODULE/visual_test.PACKAGE/'client/renderers/ScreenBlockEntityRenderer.java'
            state=renderer.with_name('ScreenBlockEntityRenderState.java')
            adapter.set_variant(stage,originals,'plain')
            baseline=(stage/visual_test.NF26_CONTROL/'ScreenBlockEntityRenderer.java').read_text()
            # Only the existing native conversion and observational submission
            # hook may differ from the immutable historical control.
            without_hook=renderer.read_text().splitlines(keepends=True)
            without_hook=''.join(line for line in without_hook if 'VisualClient.submittedTile(' not in line)
            self.assertEqual(1,without_hook.count(adapter.PLAIN_LIFECYCLE_ADAPTER+'\n'))
            without_hook=without_hook.replace(adapter.PLAIN_LIFECYCLE_ADAPTER+'\n','',1)
            self.assertEqual(adapter.modern_screen_sources.convert(baseline),without_hook)
            self.assertEqual((stage/visual_test.NF26_CONTROL/state.name).read_text(),state.read_text())
            self.assertIn('RenderTypes.text(state.texture)',renderer.read_text())
            self.assertNotIn('ScreenRenderTypes.textPolygonOffset',renderer.read_text())
            self.assertIn('state.tileMinX',renderer.read_text())
            adapter.set_variant(stage,originals,'wrong-depth')
            self.assertIn('CompareOp.LESS_THAN_OR_EQUAL, true',target.read_text())
            self.assertIn('DEPTH_BIAS_FACTOR, DEPTH_BIAS_UNITS',target.read_text())
            self.assertEqual(originals[renderer.relative_to(stage)],renderer.read_text())
            adapter.set_variant(stage,originals,'see-through')
            self.assertIn('CompareOp.ALWAYS_PASS, false, 0.0F, 0.0F',target.read_text())
            self.assertEqual(originals[renderer.relative_to(stage)],renderer.read_text())
            self.assertEqual(originals[state.relative_to(stage)],state.read_text())
            adapter.set_variant(stage,originals,'fixed')
            for path,original in originals.items():self.assertEqual(original,(stage/path).read_text())
            with self.assertRaises(ValueError):adapter.set_variant(stage,originals,'unexpected')
    def test_plain_control_rejects_tampered_immutable_provenance(self):
        with tempfile.TemporaryDirectory() as tmp:
            stage=Path(tmp);self.stage(stage);runner.prepare_snapshot(stage,'fabric-26.2')
            originals={path:(stage/path).read_text() for path in adapter.mutable_paths()}
            fixture=stage/visual_test.NF26_CONTROL/'ScreenBlockEntityRenderer.java'
            fixture.write_text(fixture.read_text()+'\n// changed\n')
            with self.assertRaises(RuntimeError):adapter.set_variant(stage,originals,'plain')

    def run_control_fixture(self,failure,probe=False,accepted=False):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);dependency=root/'fixture.jar';dependency.write_bytes(b'jar fixture')
            called=[]
            def run_variant(stage,target,variant,directory,*args,**kwargs):
                called.append(variant);directory.mkdir(parents=True)
                (directory/'graphics.json').write_text(json.dumps({key:'same backend' for key in ('graphicsVendor','graphicsRenderer','graphicsVersion')}))
                if variant=='wrong-depth':
                    (directory/'measurements.json').write_text(json.dumps(self.wrong_depth_results()))
                if variant=='plain':raise failure
            with patch.object(runner,'dependency_paths',return_value={'packetweave':dependency,'openui':dependency}), \
                 patch.object(runner.subprocess,'check_output',side_effect=['head\n','']), \
                 patch.object(runner.visual.live,'native_version',return_value='0.1.5'), \
                 patch.object(runner.visual,'snapshot',return_value='a'*64), \
                 patch.object(runner,'prepare_snapshot'),patch.object(adapter,'mutable_paths',return_value=[]), \
                 patch.object(adapter,'set_variant'),patch.object(runner.visual,'run_variant',side_effect=run_variant),patch('builtins.print'):
                if accepted: runner.run(root,'fabric-26.2',probe=probe)
                else:
                    with self.assertRaises(type(failure)):runner.run(root,'fabric-26.2',probe=probe)
            receipts=list(root.glob('build/requested-visual-evidence/fabric-26.2/*/result.json'))
            self.assertEqual(1,len(receipts));receipt=json.loads(receipts[0].read_text())
            self.assertEqual('pass' if accepted else 'fail',receipt['status'])
            if not accepted:
                with self.assertRaises(ValueError):runner.verify_receipt(receipt,'head')
            return called,receipt

    def test_historical_inconclusive_remains_explicit_with_native_controls(self):
        called,receipt=self.run_control_fixture(ValueError('INCONCLUSIVE: plain renderer did not reproduce z-fighting'),accepted=True)
        self.assertEqual(['fixed','plain','see-through','wrong-depth'],called)
        self.assertEqual({'fixed':'pass','plain':'inconclusive','see-through':'pass','wrong-depth':'pass'},receipt['variants'])
        self.assertEqual('inconclusive',receipt['historical_control']['status'])
        self.assertEqual(runner.QUALIFICATION,receipt['qualification'])
        runner.verify_receipt(receipt,'head')

    @staticmethod
    def wrong_depth_results():
        return {case['id']:dict(status='pixel-failure',frames=[dict(status='pixel-failure')
                for _ in range(runner.sample_count(case))]) for case in runner.wrong_depth_cases()}

    def test_wrong_depth_requires_all_six_directions_and_every_sample(self):
        results=self.wrong_depth_results()
        self.assertEqual(6,len(results))
        self.assertEqual(24,sum(len(r['frames']) for r in results.values()))
        self.assertTrue(all(case in runner.cases('fixed') for case in runner.wrong_depth_cases()))
        runner.validate_wrong_depth(results)
        key=next(iter(results))
        for mutation in ('missing','extra','pass','incomplete','one-pass','infrastructure'):
            broken=copy.deepcopy(results)
            if mutation=='missing':broken.pop(key)
            elif mutation=='extra':broken['unexpected']=broken[key]
            elif mutation=='pass':broken[key]['status']='pass'
            elif mutation=='incomplete':broken[key]['frames'].pop()
            elif mutation=='one-pass':broken[key]['frames'][0]['status']='pass'
            else:broken[key]['frames'][0]['status']='error'
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):runner.validate_wrong_depth(broken)

    def test_native_setup_other_oracle_and_probe_failures_do_not_continue(self):
        for failure,probe in [(RuntimeError('server failed during startup'),False),
                              (ValueError('incomplete manifest'),False),
                              (ValueError('INCONCLUSIVE: plain renderer did not reproduce z-fighting'),True)]:
            with self.subTest(failure=str(failure),probe=probe):
                called,receipt=self.run_control_fixture(failure,probe)
                self.assertEqual(['fixed','plain'],called)
                self.assertNotIn('see-through',receipt['variants'])

    def test_receipt_requires_clean_full_fixed_and_negative_control_evidence(self):
        valid=dict(schema=2,qualification=runner.QUALIFICATION,head='head',dirty=False,target='fabric-26.2',status='pass',
            source_sha256='a'*64,variants={'fixed':'pass','plain':'inconclusive','see-through':'pass','wrong-depth':'pass'},
            historical_control={'variant':'plain','status':'inconclusive'},
            wrong_depth_cases=[case['id'] for case in runner.wrong_depth_cases()],
            dependencies={name:{'sha256':'b'*64} for name in ('packetweave','openui')},
            graphics={key:'recorded' for key in ('graphicsVendor','graphicsRenderer','graphicsVersion')})
        runner.verify_receipt(valid,'head')
        for key,value in [('head','old'),('dirty',True),('status','probe-only'),('status','compile-only'),
                          ('variants',{'fixed':'pass'}),('schema',1),('qualification','old'),
                          ('historical_control',{'variant':'plain','status':'pass'}),('wrong_depth_cases',[]),('source_sha256',''),('dependencies',{}),('graphics',{})]:
            altered=copy.deepcopy(valid);altered[key]=value
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):runner.verify_receipt(altered,'head')
if __name__=='__main__':unittest.main()
