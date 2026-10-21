"""Test-only 26.2 adaptation of the calibrated common framebuffer drivers."""
from pathlib import Path
import re
from visual_test import PACKAGE, replace_once
from visual_cases import SAMPLES
import modern_screen_sources

MODULE = 'common-26.2'

def adapt_driver(source, name):
    if name == 'VisualClient':
        # Diagnostics only: keep queue, compiled/visibility and stable-frame gates
        # unchanged, but identify the exact gate/cell instead of a generic timeout.
        for old, new, expected in (
            ('if (!__TERRAIN_IDLE__) return false;',
             'if (!__TERRAIN_IDLE__) { waitingFor = "native terrain worker queue"; return false; }', 1),
            ('if (!__SECTION_COMPILED__) return false;',
             'if (!__SECTION_COMPILED__) { waitingFor = "native compiled/visible section at " + pos; return false; }', 2),
            ('                waitingFor = "terrain rebuild";\n', '', 1),
        ):
            if source.count(old) != expected:
                raise ValueError('Native visual readiness diagnostic anchor drift: ' + old)
            source = source.replace(old, new)
    replacements = {
        '__BASE_SAMPLES__': str(SAMPLES),
        'Blocks.RED_CONCRETE': 'Blocks.CONCRETE.red()',
        '__TELEPORT__': 'player.teleportTo(level, eye.x, eye.y-player.getEyeHeight(), eye.z, Set.of(), yaw, pitch, true);',
        '__CAMERA_POSITION__': 'camera.position()',
        '__CAMERA_YAW__': 'camera.yRot()',
        '__CAMERA_PITCH__': 'camera.xRot()',
        '__TERRAIN_IDLE__': 'mc.levelRenderer.hasRenderedAllSections()',
        '__SECTION_COMPILED__': 'mc.levelRenderer.isSectionCompiledAndVisible(pos)',
        '__CHUNK_LOADED__': 'mc.level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) != null',
        '__CAPTURE__': 'Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), pixels -> save(pixels, frame, stem));',
        'mc.options.hideGui = true;': 'if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();',
        'mc.gameRenderer.getMainCamera()': 'mc.gameRenderer.mainCamera()',
        'mc.levelRenderer.allChanged()': 'mc.levelRenderer.invalidateCompiledGeometry(mc.level, mc.options, mc.gameRenderer.mainCamera(), mc.getBlockColors())',
        'mc.getOverlay()': 'mc.gui.overlay()',
        'mc.screen': 'mc.gui.screen()',
        'org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR)': 'com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().vendorName()',
        'org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER)': 'com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().name()',
        'org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION)': 'com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName() + ": " + com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().driverInfo()',
    }
    for old, new in replacements.items():
        source = source.replace(old, new)
    if re.search(r'__[A-Z_]+__', source):
        raise ValueError('Unexpanded visual driver token in ' + name)
    if 'org.lwjgl.opengl' in source:
        raise ValueError('26.2 visual driver must support non-GL backends')
    return source

def instrument(stage):
    package = stage / MODULE / PACKAGE
    # Test-only copies are derived into this disposable overlay, not maintained
    # as production duplicates. The normal generator will include these hooks.
    for relative in ('ServerTickScheduler.java', 'client/testing/UiSmokeTest.java', 'client/renderers/ScreenBlockEntityRenderer.java'):
        canonical = stage / 'neoforge-26.1.2' / PACKAGE / relative
        target = package / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(modern_screen_sources.convert(canonical.read_text()))
    drivers = package / 'testing/visual'
    drivers.mkdir(parents=True, exist_ok=True)
    for name in ('VisualServer', 'VisualClient'):
        source = (stage / 'tools/visual' / (name + '.java')).read_text()
        (drivers / (name + '.java')).write_text(adapt_driver(source, name))
    replace_once(package / 'ServerTickScheduler.java',
                 'private static void process(MinecraftServer server) {',
                 'private static void process(MinecraftServer server) {\n        com.nstut.simplyscreens.testing.visual.VisualServer.tick(server);')
    replace_once(package / 'client/testing/UiSmokeTest.java',
                 'public static boolean tick(Minecraft client) {',
                 'public static boolean tick(Minecraft client) {\n        if (System.getenv("SS_VISUAL_DIR") != null) return com.nstut.simplyscreens.testing.visual.VisualClient.tick(client);')
    mixin = package / 'client/mixin/GameRendererMixin.java'
    replace_once(mixin, 'ScreenColorRestore.beginFrame();',
                 'ScreenColorRestore.beginFrame(); com.nstut.simplyscreens.testing.visual.VisualClient.beginRenderFrame();')
    replace_once(mixin, 'ScreenColorRestore.finishWorld();',
                 'ScreenColorRestore.finishWorld(); com.nstut.simplyscreens.testing.visual.VisualClient.afterRender(net.minecraft.client.Minecraft.getInstance());')
    renderer = package / 'client/renderers/ScreenBlockEntityRenderer.java'
    replace_once(renderer, '        if (!state.visible || state.texture == null',
                 '        if (com.nstut.simplyscreens.testing.visual.VisualClient.skipAnchorOwner(state.anchorOffsetX == 0 && state.anchorOffsetY == 0 && state.anchorOffsetZ == 0)) return;\n        if (!state.visible || state.texture == null')
    replace_once(renderer, 'debugDraw(state);',
                 'debugDraw(state);\n        com.nstut.simplyscreens.testing.visual.VisualClient.submittedScreen(state.facing, state.width, state.height, state.blockPos.getX() + state.anchorOffsetX, state.blockPos.getY() + state.anchorOffsetY, state.blockPos.getZ() + state.anchorOffsetZ, state.blockPos.getX(), state.blockPos.getY(), state.blockPos.getZ());')

def mutable_paths():
    return [Path(MODULE) / PACKAGE / 'client/renderers/ScreenRenderTypes.java']

def set_variant(stage, originals, variant):
    for path, text in originals.items():
        (stage / path).write_text(text)
    if variant == 'fixed':
        return
    path = stage / mutable_paths()[0]
    text = path.read_text()
    old = 'new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true,\n                    DEPTH_BIAS_FACTOR, DEPTH_BIAS_UNITS)'
    if text.count(old) != 1:
        raise RuntimeError('26.2 visual depth-state mutation anchor changed')
    replacement = ('new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, 0.0F, 0.0F)'
                   if variant == 'plain' else 'new DepthStencilState(CompareOp.ALWAYS, false, 0.0F, 0.0F)')
    if variant not in ('plain', 'see-through'):
        raise ValueError('Unknown visual variant: ' + variant)
    path.write_text(text.replace(old, replacement))
