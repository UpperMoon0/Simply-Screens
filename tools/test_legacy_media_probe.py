"""Regression contracts for generated native legacy media smoke probes, not runtime evidence."""
from pathlib import Path
import tempfile
import unittest
import legacy_screen_sources as legacy
import forge_1122_sources as forge112
import forge_1165_sources as forge116

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path('com/nstut/simplyscreens')
GAME = ROOT / 'common-legacy-game/src/main/java' / PACKAGE


class LegacyMediaProbeSourceTest(unittest.TestCase):
    def test_one_authoritative_gameplay_probe_is_generated_for_all_three_targets(self):
        for generator in (legacy, forge112, forge116):
            with self.subTest(generator=generator.__name__), tempfile.TemporaryDirectory() as directory:
                output = Path(directory)
                generator.generate(output)
                for source in GAME.rglob('*.java'):
                    self.assertTrue((output / PACKAGE / source.relative_to(GAME)).is_file(), str(source))
                client = (output / PACKAGE / 'client/testing/LegacyMediaClientProbe.java').read_text()
                self.assertIn('new UploadImageChunkC2SPacket(', client)
                self.assertIn('ClientImageManager.getTextureLocation(image)', client)
                self.assertNotIn('setXRot(', client)
                self.assertNotIn('setYRot(', client)
                self.assertIn('savedPitch;', client)
                if generator is forge112:
                    self.assertIn('pixels.buffered().getRGB(x, y)', client)
                    self.assertIn('texture.getGlTextureId() > 0', client)
                else:
                    self.assertIn('pixels.getPixelRGBA(x, y)', client)
                    self.assertIn('texture.getId() > 0', client)

    def test_server_and_codec_never_reference_client_classes(self):
        for name in ('LegacyMediaServerProbe.java', 'LegacyMediaProbePacket.java'):
            source = (GAME / 'testing' / name).read_text()
            self.assertNotIn('net.minecraft.client', source)
            self.assertNotIn('LegacyMediaClientProbe', source)
        server = (GAME / 'testing/LegacyMediaServerProbe.java').read_text()
        for text in ('!LegacyMediaProbeProtocol.ENABLED', 'level.isEmptyBlock(pos)', 'player.mayInteract(level, pos)',
                     'screen.claimPlaced(player.getUUID())', 'ScreenPacketSecurity.canModify(player, pos)',
                     'active.owner.equals(player.getUUID())', 'active.session.equals(packet.session)',
                     'active.display.equals(packet.display)', 'active.pos.equals(packet.pos)',
                     'active.owner.toString().equals(metadata.getOwnerUUID())', 'ownedBlock(active)',
                     'ownedImage(server, active, image)', 'LegacyMediaProbeProtocol.TIMEOUT_NANOS'):
            self.assertIn(text, server)
        for forbidden in ('setGameMode', 'setPermissionLevel', 'setOp', 'clearCache()', 'clearUploads()', 'net.minecraft.client'):
            self.assertNotIn(forbidden, server)

    def test_direction_and_property_guards_precede_decode(self):
        for name, side in (('LegacyMediaServerProbe.java', 'true'), ('LegacyMediaClientProbe.java', 'false')):
            path = GAME / ('testing' if side == 'true' else 'client/testing') / name
            source = path.read_text()
            generated = legacy.convert(source, name)
            self.assertIn('DirectionalReceivers.register(', generated)
            guard = generated.index('if (!LegacyMediaProbeProtocol.ENABLED) return;', generated.index('DirectionalReceivers.register('))
            self.assertLess(guard, generated.index('LegacyMediaProbePacket.read(buf, ' + side + ')'))
        codec = (GAME / 'testing/LegacyMediaProbePacket.java').read_text()
        self.assertIn('packet.serverbound() != serverbound || buf.isReadable()', codec)
        self.assertNotIn('readByteArray', codec)
        self.assertNotIn('readUtf', codec)
        self.assertIn('readUnsignedByte()', codec)
        native = (ROOT / 'forge-1.12.2/src/main/java' / PACKAGE / 'compat/NetworkManager.java').read_text()
        self.assertIn('deliver(message,new PacketContext()', native)
        self.assertIn('},C2S)', native)
        self.assertIn('deliver(message,context,S2C)', native)

    def test_renderer_observation_happens_after_native_submission_and_is_opt_in(self):
        canonical = (ROOT / 'common-1.20.1/src/main/java' / PACKAGE / 'client/renderers/ScreenBlockEntityRenderer.java').read_text()
        generated = legacy.convert(canonical, 'ScreenBlockEntityRenderer.java')
        native = (ROOT / 'forge-1.12.2/src/main/java' / PACKAGE / 'client/renderers/ScreenBlockEntityRenderer.java').read_text()
        for source, call in ((generated, 'renderTextureQuad(texture, tile, poseStack, bufferSource, packedOverlay);'),
                             (native, 'Tessellator.getInstance().draw();')):
            self.assertLess(source.index(call), source.index('LegacyMediaClientProbe.geometrySubmitted('))
            self.assertIn('if' + ('(' if source is native else ' (') + 'com.nstut.simplyscreens.testing.LegacyMediaProbeProtocol.ENABLED)', source)
        self.assertIn('createFloatBuffer(16)', native)
        self.assertNotIn('LegacyMediaClientProbe', canonical)

    def test_client_pass_requires_all_evidence_and_cleanup_not_framebuffer_claim(self):
        source = (GAME / 'client/testing/LegacyMediaClientProbe.java').read_text()
        self.assertIn('!cleanupRequested || !evidence.passed() || !evidence.image().equals(packet.image)', source)
        self.assertIn('display.equals(screen.getDisplayId())', source)
        self.assertIn('pos.equals(screen.getBlockPos())', source)
        self.assertIn('registeredTexture.equals(texture)', source)
        self.assertIn('framebufferPixels=not_tested', source)
        self.assertNotIn('System.exit(0)', source)
        self.assertNotIn('connect(', source)
        self.assertNotIn('allowsMultiplayer', source)
        self.assertNotIn('clearCache()', source)
        self.assertIn('ClientImageManager.invalidateImage(evidence.image())', source)

    def test_download_hook_observes_production_assembly_without_replacing_decode(self):
        source = (ROOT / 'common-1.20.1/src/main/java' / PACKAGE / 'helpers/ClientImageManager.java').read_text()
        generated = legacy.convert(source, 'ClientImageManager.java')
        self.assertLess(generated.index('DOWNLOADS.accept('), generated.index('LegacyMediaClientProbe.downloaded('))
        self.assertIn('saveImageToCache(imageId, download.extension(), download.bytes(), epoch, transferId)', generated)
        self.assertLess(generated.index('nativeImage = MediaFiles.decodeAndWriteAtomically('), generated.index('LegacyMediaClientProbe.downloaded('))
        for generator in (legacy, forge112):
            canonical = (ROOT / 'common-1.20.1/src/main/java' / PACKAGE / 'network/PacketRegistries.java').read_text()
            result = generator.convert(canonical, 'PacketRegistries.java')
            self.assertIn('LegacyMediaProbePacket) packet).channel()', result)
            self.assertIn('LegacyMediaProbePacket) packet).write(buf)', result)

    def test_completed_upload_hook_keeps_real_security_checks_and_exact_level(self):
        source = (ROOT / 'common-1.20.1/src/main/java' / PACKAGE / 'helpers/ServerImageManager.java').read_text()
        for generator in (legacy, forge112):
            result = generator.convert(source, 'ServerImageManager.java')
            self.assertIn('ScreenPacketSecurity.canModify(player, blockPos)', result)
            self.assertIn('LegacyMediaServerProbe.uploadStored(player, state.level, blockPos, imageId, state.originalName)', result)
            self.assertLess(result.index('screen.setImageId(imageId)'), result.index('LegacyMediaServerProbe.uploadStored('))
        server = (GAME / 'testing/LegacyMediaServerProbe.java').read_text()
        self.assertIn('active.level != uploadLevel', server)
        self.assertIn('active == retired', server)
        duplicate = server[server.index('else if (!active.image.equals(image)) {'):server.index('private static boolean ownedBlock')]
        self.assertLess(duplicate.index('ServerImageManager.deleteImage('), duplicate.index('fail(player.getServer()'))
        self.assertIn('ServerImageManager.deleteImage(player.getServer(), image, active.owner.toString())', server)

    def test_deadlines_and_owned_cleanup_guard_every_async_completion(self):
        client = (GAME / 'client/testing/LegacyMediaClientProbe.java').read_text()
        receive = client[client.index('private static void receiveActive'):client.index('public static void downloaded(')]
        self.assertLess(receive.index('requireLive(Minecraft.getInstance())'), receive.index('complete = true'))
        self.assertIn('catch (RuntimeException failure) { throw abort(failure); }', client)
        self.assertIn('try { releaseOwnedClientState(); }', client)
        server = (GAME / 'testing/LegacyMediaServerProbe.java').read_text()
        receive = server[server.index('private static void receive('):server.index('private static Fixture place(')]
        self.assertLess(receive.index('if (expired(active)'), receive.index('LegacyMediaProbePacket.CLEANED'))
        self.assertIn('LifecycleEvent.SERVER_STOPPING.register', server)
        stopped = server[server.index('LifecycleEvent.SERVER_STOPPED.register'):server.index('private static void receive(')]
        self.assertNotIn('cleanup(', stopped)
        native = (ROOT / 'forge-1.12.2/src/main/java' / PACKAGE / 'forge/SimplyScreensForge.java').read_text()
        self.assertIn('stopping(FMLServerStoppingEvent event)', native)
        self.assertIn('LifecycleEvent.SERVER_STOPPING.fire(server)', native)
        self.assertIn('LifecycleEvent.SERVER_STOPPED.fire(server)', native)

    def test_active_lifecycle_validation_precedes_outer_null_world_returns(self):
        source=(GAME/'client/testing/LegacyMediaClientProbe.java').read_text()
        entry=source[source.index('public static void validateActiveLifecycle'):source.index('private static boolean tickActive')]
        self.assertIn('!requested || complete || failed',entry)
        self.assertIn('requireLive(client)',entry);self.assertIn('throw abort(failure)',entry)
        self.assertNotIn('initialize()',entry);self.assertNotIn('sendToServer',entry)
        self.assertIn('LegacyMediaProbeProtocol.requireSession(',source)
        native=(ROOT/'common-1.16.5/src/main/java'/PACKAGE/'client/testing/NativeClientAcceptance.java').read_text()
        self.assertLess(native.index('LegacyMediaClientProbe.validateActiveLifecycle(client)'),native.index('if (client.player == null'))
        legacy=(ROOT/'forge-1.12.2/src/main/java'/PACKAGE/'forge/ClientProxy.java').read_text()
        self.assertLess(legacy.index('LegacyMediaClientProbe.validateActiveLifecycle(client)'),legacy.index('client.player==null'))

    def test_native_112_placed_model_capture_follows_real_geometry_before_owned_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory);forge112.generate(output)
            client=(output/PACKAGE/'client/testing/LegacyMediaClientProbe.java').read_text()
            passed=client.index('if (evidence.passed()) {')
            captured=client.index('NativePlacedModelProbe.verifyAndCapture(client, screen, session);')
            cleanup=client.index('cleanupRequested = true;')
            self.assertLess(passed,captured);self.assertLess(captured,cleanup)
        native=(ROOT/'forge-1.12.2/src/main/java'/PACKAGE/'client/testing/NativePlacedModelProbe.java').read_text()
        for guard in ('Boolean.getBoolean("simplyscreens.mediaSmokeTest")', 'getModelForState(state)',
                      'getMissingModel()', 'getMissingSprite()', 'quads.isEmpty()', 'quad.getSprite()',
                      'ScreenShotHelper.saveScreenshot(', 'capture.exists()', 'ImageIO.read(capture)'):
            self.assertIn(guard,native)
        self.assertNotIn('NativePlacedModelProbe', (GAME/'client/testing/LegacyMediaClientProbe.java').read_text())

    def test_hook_drift_fails_closed(self):
        for name in ('SimplyScreens.java', 'PacketRegistries.java', 'ClientImageManager.java', 'ServerImageManager.java', 'ScreenBlockEntityRenderer.java'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                legacy.legacy_media_hooks('class Incomplete {}', name)


if __name__ == '__main__':
    unittest.main()
