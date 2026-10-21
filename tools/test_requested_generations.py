import json
from pathlib import Path
import tempfile
import unittest
import legacy_screen_sources
import port_resources
import forge_1165_sources
ROOT=Path(__file__).resolve().parents[1]
class RequestedGenerationsTest(unittest.TestCase):
    def test_legacy_generator_preserves_full_runtime(self):
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp);legacy_screen_sources.generate(output)
            expected={p.relative_to(ROOT/folder) for folder in ['common/src/main/java','common-1.20.1/src/main/java'] for p in (ROOT/folder).rglob('*.java')}
            actual={p.relative_to(output) for p in output.rglob('*.java')}
            self.assertTrue(expected<=actual,expected-actual)
            for name in ['api/ScreenApi.java','api/ScreenCommands.java','helpers/ScreenPacketSecurity.java','helpers/ServerImageManager.java','client/screens/ImageLoadScreen.java','network/PacketRegistries.java']:
                self.assertIn(Path('com/nstut/simplyscreens')/name,actual)
            url=(output/'com/nstut/simplyscreens/network/DownloadImageFromUrlC2SPacket.java').read_text()
            self.assertNotIn('java.net.http',url)
            self.assertIn('ScreenPacketSecurity.canModify',url)
            self.assertIn('PublicImageHttp.get(url)',url)
            self.assertIn('MAX_URL_DOWNLOAD_SIZE',url)
    def test_legacy_axis_and_attachment_hooks_are_not_silently_changed(self):
        ordinary='class A { enum Axis { HEIGHT } Axis value=Axis.HEIGHT; }'
        self.assertIn('Axis.HEIGHT',legacy_screen_sources.convert(ordinary,'A.java'))
        entity=(ROOT/'common-1.20.1/src/main/java/com/nstut/simplyscreens/blocks/entities/ScreenBlockEntity.java').read_text()
        generated=legacy_screen_sources.convert(entity,'ScreenBlockEntity.java')
        self.assertIn('setLevelAndPosition(net.minecraft.world.level.Level level, BlockPos pos)',generated)
        self.assertIn('super.setLevelAndPosition(level, pos)',generated)
        self.assertIn('ScreenLoadReconciler.enqueue',generated)
        self.assertIn('Direction.Axis.Z',generated)
    def test_forge_translates_nested_renderer_classes_and_jvm_descriptors(self):
        sample='import net.minecraft.client.renderer.RenderStateShard; class Layer extends RenderStateShard { LayeringStateShard bias; }'
        self.assertIn('LayerState bias',forge_1165_sources.convert(sample))
        descriptor='Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lcom/mojang/math/Matrix4f;)V'
        transformed=forge_1165_sources.convert(descriptor)
        self.assertIn('WorldRenderer;renderLevel(Lcom/mojang/blaze3d/matrix/MatrixStack;',transformed)
        self.assertIn('Lnet/minecraft/client/renderer/ActiveRenderInfo;',transformed)
        self.assertNotIn('com/mojang/math/Matrix4f',transformed)
    def test_native_recipes_and_language(self):
        for version,loader in [('1.16.5','fabric'),('1.16.5','forge'),('1.12.2','forge')]:
            with self.subTest(version=version,loader=loader), tempfile.TemporaryDirectory() as tmp:
                output=Path(tmp);port_resources.generate(version,loader,output)
                root='assets' if version=='1.12.2' else 'data'
                recipe=json.loads((output/f'{root}/simply_screens/recipes/screen.json').read_text())
                self.assertEqual(recipe['result']['item'],'simply_screens:screen')
                self.assertEqual(recipe['key']['A']['item'],'minecraft:quartz')
                self.assertNotIn('category',recipe)
                lang='en_us.lang' if version=='1.12.2' else 'en_us.json'
                self.assertTrue((output/'assets/simply_screens/lang'/lang).is_file())
    def test_26_rendering_uses_backend_neutral_api(self):
        root=ROOT/'common-26.2/src/main/java/com/nstut/simplyscreens/client/renderers'
        source='\n'.join(p.read_text() for p in root.glob('*.java'))
        self.assertNotIn('org.lwjgl.opengl',source)
        self.assertIn('withBindGroupLayout',source)
        self.assertIn('transientMemory().uploadGpu',source)
        self.assertIn('encoder.submit()',source)
        self.assertNotIn('CompareOp.LESS_THAN_OR_EQUAL',source)
        self.assertIn('CompareOp.GREATER_THAN_OR_EQUAL',source)
    def test_legacy_direction_is_checked_before_packet_decode(self):
        source=(ROOT/'common-1.20.1/src/main/java/com/nstut/simplyscreens/network/PacketRegistries.java').read_text()
        generated=legacy_screen_sources.convert(source,'PacketRegistries.java')
        self.assertEqual(18,generated.count('DirectionalReceivers.register('))
        guard=(ROOT/'common-1.16.5/src/main/java/com/nstut/simplyscreens/compat/DirectionalReceivers.java').read_text()
        self.assertIn('context.getEnvironment() == expected',guard)
        self.assertLess(guard.index('context.getEnvironment() == expected'),guard.index('receiver.receive(buffer, context)'))
    def test_legacy_optional_guide_uses_native_serializer(self):
        for loader in ('fabric','forge'):
            with tempfile.TemporaryDirectory() as tmp:
                output=Path(tmp);port_resources.generate('1.16.5',loader,output)
                recipe=json.loads((output/'data/simply_screens/recipes/guide_book.json').read_text())
                self.assertEqual('simply_screens:guide_book',recipe['type'])
                book=json.loads((output/'data/simply_screens/patchouli_books/guide/book.json').read_text())
                self.assertEqual('simply_screens:item/guide_book',book['model'])
                self.assertNotIn('amethyst shard',(output/'assets/simply_screens/patchouli_books/guide/en_us/entries/guide.json').read_text())
    def test_forge_local_native_jars_use_actual_dependency_remapping(self):
        source=(ROOT/'forge-1.16.5/build.gradle').read_text()
        self.assertNotIn('fg.deobf(files(',source)
        self.assertIn('flatDir',source)
        self.assertIn('includeModule',source)
        self.assertIn('fg.deobf("com.nstut.portdeps:',source)
    def test_ci_version_parser_accepts_repository_properties(self):
        workflow=(ROOT/'.github/workflows/requested-generations.yml').read_text()
        self.assertIn("mod_version[[:space:]]*=[[:space:]]*",workflow)
        self.assertIn('test -n "$version"',workflow)
    def test_gradle_inputs_use_supported_directory_api(self):
        self.assertNotIn('inputs.dirs', (ROOT/'gradle/legacy-port.gradle').read_text())
    def test_neoforge_runtime_configuration_is_explicit(self):
        source=(ROOT/'neoforge-26.2/build.gradle').read_text()
        self.assertNotIn('localRuntime ',source)
        self.assertIn('runtimeOnly files(portPacketweaveJar)',source)
    def test_native_source_archives_and_jabel_jvm_arguments(self):
        modern=(ROOT/'gradle/requested-port.gradle').read_text()
        self.assertIn('sourcesJar { duplicatesStrategy=DuplicatesStrategy.EXCLUDE }',modern)
        legacy=(ROOT/'gradle/legacy-port.gradle').read_text()
        self.assertEqual(2,legacy.count('singleFile}".toString()'))
    def test_legacy_asset_retry_never_retries_runtime_or_assertions(self):
        workflow=(ROOT/'.github/workflows/requested-generations.yml').read_text()
        retry=workflow.split('name: Prefetch official legacy assets',1)[1].split('      - name:',1)[0]
        self.assertIn(' downloadAssets ',retry)
        self.assertIn('SocketTimeoutException',retry)
        self.assertNotIn('runLiveJoinTest',retry)
    def test_no_unofficial_112_fabric_target(self):
        self.assertFalse((ROOT/'fabric-1.12.2').exists())
if __name__=='__main__':unittest.main()
