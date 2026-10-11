"""Fast contracts complement (never replace) the real shader framebuffer matrix."""
import json
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]

class ShaderColorContractTest(unittest.TestCase):
    def test_world_bloom_pass_remains_and_restore_copies_matrices(self):
        for version in ('1.20.1', '1.21.1'):
            base = ROOT / f'common-{version}/src/main/java/com/nstut/simplyscreens/client'
            renderer = (base/'renderers/ScreenBlockEntityRenderer.java').read_text()
            self.assertIn('textPolygonOffset(texture)', renderer)
            self.assertIn('ScreenColorRestore.capture', renderer)
            restore = (base/'renderers/ScreenColorRestore.java').read_text()
            self.assertIn('new Matrix4f(RenderSystem.getProjectionMatrix())', restore)
            self.assertIn('new Matrix4f(RenderSystem.getModelViewMatrix())', restore)
            self.assertIn('new Matrix4f(pose.pose())', restore)
            self.assertIn('finally {\n            FRAME.clear();', restore)
            self.assertIn('ShaderPackState.shouldCapture()', restore)

    def test_hook_is_after_level_not_tail_or_loader_event(self):
        for version in ('1.20.1', '1.21.1'):
            base = ROOT / f'common-{version}/src/main'
            hook = (base/'java/com/nstut/simplyscreens/client/mixin/GameRendererMixin.java').read_text()
            self.assertIn('Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(', hook)
            self.assertIn('shift = At.Shift.AFTER', hook)
            self.assertNotIn('@At("TAIL")', hook)
            config = json.loads((base/'resources/simplyscreens.mixins.json').read_text())
            self.assertEqual(1, config['injectors']['defaultRequire'])
            self.assertEqual(['GameRendererMixin'], config['client'])

    def test_late_pass_is_depth_tested_opaque_replacement_not_alpha_reblend(self):
        for version in ('1.20.1', '1.21.1'):
            base = ROOT / f'common-{version}/src/main'
            state = (base/'java/com/nstut/simplyscreens/client/renderers/ScreenColorRenderTypes.java').read_text()
            for token in ('LEQUAL_DEPTH_TEST', 'COLOR_WRITE', 'NO_TRANSPARENCY'):
                self.assertIn(token, state)
            shader = (base/'resources/assets/simply_screens/shaders/core/color_restore.fsh').read_text()
            self.assertIn('if (source.a < 0.99999) discard;', shader)
            self.assertIn('fragColor = vec4(source.rgb, 1.0);', shader)
            self.assertNotIn('fog(', shader)

    def test_modern_adapter_preserves_depth_and_resource_lifecycle(self):
        base = ROOT/'neoforge-26.1.2/src/main'
        replay = (base/'java/com/nstut/simplyscreens/client/renderers/ScreenColorRestore.java').read_text()
        self.assertIn('ColorTargetState.DEFAULT', replay)
        self.assertIn('CompareOp.LESS_THAN_OR_EQUAL, false, -1.0F, -128.0F', replay)
        self.assertIn('target.getDepthTextureView(), OptionalDouble.empty()', replay)
        self.assertIn('new Matrix4f(modelView), new Matrix4f(pose)', replay)
        self.assertIn('FRAME.clear()', replay)
        setup = (base/'java/com/nstut/simplyscreens/neoforge/SimplyScreensClient.java').read_text()
        self.assertIn('RegisterRenderPipelinesEvent', setup)
        fragment = (base/'resources/assets/simply_screens/shaders/core/screen_color_restore.fsh').read_text()
        self.assertIn('0.99999', fragment)

    def test_loader_registrations_are_client_only(self):
        for target in ('fabric-1.20.1', 'fabric-1.21.1'):
            metadata = json.loads((ROOT/target/'src/main/resources/fabric.mod.json').read_text())
            self.assertIn('simplyscreens.mixins.json', metadata['mixins'])
            setup=(ROOT/target/'src/main/java/com/nstut/simplyscreens/fabric/client/ClientSetup.java').read_text()
            self.assertIn('CoreShaderRegistrationCallback.EVENT', setup)
        for target in ('forge-1.20.1', 'neoforge-1.21.1'):
            setup=(ROOT/target/f'src/main/java/com/nstut/simplyscreens/{target.split("-")[0]}/client/ScreenColorShaders.java').read_text()
            self.assertIn('Dist.CLIENT', setup)
            self.assertIn('RegisterShadersEvent', setup)

if __name__ == '__main__': unittest.main()
