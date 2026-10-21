import copy
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
            self.assertEqual(adapter.modern_screen_sources.convert(baseline),without_hook)
            self.assertEqual((stage/visual_test.NF26_CONTROL/state.name).read_text(),state.read_text())
            self.assertIn('RenderTypes.text(state.texture)',renderer.read_text())
            self.assertNotIn('ScreenRenderTypes.textPolygonOffset',renderer.read_text())
            self.assertIn('state.tileMinX',renderer.read_text())
            adapter.set_variant(stage,originals,'see-through')
            self.assertIn('CompareOp.ALWAYS, false, 0.0F, 0.0F',target.read_text())
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

    def test_receipt_requires_clean_full_fixed_and_negative_control_evidence(self):
        valid=dict(head='head',dirty=False,target='fabric-26.2',status='pass',
            source_sha256='a'*64,variants={'fixed':'pass','plain':'pass','see-through':'pass'},
            dependencies={name:{'sha256':'b'*64} for name in ('packetweave','openui')},
            graphics={key:'recorded' for key in ('graphicsVendor','graphicsRenderer','graphicsVersion')})
        runner.verify_receipt(valid,'head')
        for key,value in [('head','old'),('dirty',True),('status','probe-only'),('status','compile-only'),
                          ('variants',{'fixed':'pass'}),('source_sha256',''),('dependencies',{}),('graphics',{})]:
            altered=copy.deepcopy(valid);altered[key]=value
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):runner.verify_receipt(altered,'head')
if __name__=='__main__':unittest.main()
