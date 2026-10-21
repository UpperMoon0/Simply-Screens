"""Test-only 26.2 adaptation of the calibrated common framebuffer drivers."""
from pathlib import Path
import re
from visual_test import (PACKAGE, replace_once, NF26_CONTROL, NF26_WORLD_MODELS,
                         verify_nf26_control, restore_nf26_control_models)
from visual_cases import SAMPLES
import modern_screen_sources

MODULE = 'common-26.2'
# Loader ABI only. This historical per-tile control has no frame claims or
# level-keyed cache; its sole persistent map throttles diagnostic log output.
PLAIN_LIFECYCLE_ADAPTER = """
    public static void beginRenderFrame() { }
    public static void clearLevel(Object levelIdentity) { }
    public static void clearCaches() { LAST_DRAW_LOG_NANOS.clear(); }
"""


def adapt_driver(source, name):
    if name == 'VisualClient':
        # Diagnostics only: keep queue, compiled/visibility and stable-frame gates
        # unchanged, but identify the exact gate/cell instead of a generic timeout.
        for old, new, expected in (
            ('if (!__TERRAIN_IDLE__) return false;',
             'if (!__TERRAIN_IDLE__) { waitingFor = "native terrain worker queue"; return false; }', 1),
            ('if (!__SECTION_COMPILED__) return false;',
             'if (!__SECTION_COMPILED__) { waitingFor = sectionReadiness(mc, pos); return false; }', 2),
            ('                waitingFor = "terrain rebuild";\n', '', 1),
        ):
            if source.count(old) != expected:
                raise ValueError('Native visual readiness diagnostic anchor drift: ' + old)
            source = source.replace(old, new)
        marker = '    private static boolean terrainReady(Minecraft mc) {'
        if source.count(marker) != 1:
            raise ValueError('Native visual terrain method anchor drift')
        # Read-only diagnostics distinguish absent/uncompiled meshes from upload
        # fade without replacing or weakening the native readiness predicate.
        source = source.replace(marker, '''    private static String sectionReadiness(Minecraft mc, BlockPos pos) {
        String detail = "native compiled/visible section at " + pos;
        var area = mc.levelRenderer.viewArea();
        if (area == null) return detail + "; view area absent";
        var section = area.getRenderSectionAt(pos);
        if (section == null) return detail + "; render section absent";
        var mesh = section.sectionMesh.get();
        return detail + "; origin=" + section.getRenderOrigin()
                + "; mesh=" + (mesh == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED
                    ? "UNCOMPILED" : mesh.getClass().getSimpleName())
                + "; fade=" + section.getVisibility(net.minecraft.util.Util.getMillis())
                + "; listedVisible=" + mc.levelRenderer.visibleSections().contains(section);
    }

''' + marker, 1)
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
        # Native reload-chunks resets extraction dirty state before invalidating
        # render meshes. Calling only the render-side half can strand a visible
        # UNCOMPILED section behind an already-clean extraction tracker.
        'mc.levelRenderer.allChanged()': 'mc.levelExtractor.allChanged()',
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
    for relative in ('ServerTickScheduler.java', 'client/testing/UiSmokeTest.java', 'client/renderers/ScreenBlockEntityRenderer.java', 'client/renderers/ScreenBlockEntityRenderState.java'):
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
    return [Path(MODULE) / PACKAGE / 'client/renderers' / name for name in (
        'ScreenRenderTypes.java', 'ScreenBlockEntityRenderer.java', 'ScreenBlockEntityRenderState.java'
    )] + list(NF26_WORLD_MODELS)

def set_variant(stage, originals, variant):
    for path, text in originals.items():
        (stage / path).write_text(text)
    if variant == 'fixed':
        return
    if variant == 'plain':
        # Reuse the same hash-locked pre-fix per-tile baseline as 26.1.2.
        # Merely zeroing bias on the fixed whole-screen topology is not the
        # calibrated historical regression and can legitimately remain clean.
        verify_nf26_control(stage)
        restore_nf26_control_models(stage)
        for name in ('ScreenBlockEntityRenderer.java', 'ScreenBlockEntityRenderState.java'):
            source = (stage / NF26_CONTROL / name).read_text()
            target = stage / MODULE / PACKAGE / 'client/renderers' / name
            target.write_text(modern_screen_sources.convert(source))
        renderer = stage / MODULE / PACKAGE / 'client/renderers/ScreenBlockEntityRenderer.java'
        replace_once(renderer, '    private static final Map<BlockPos, Long> LAST_DRAW_LOG_NANOS', PLAIN_LIFECYCLE_ADAPTER + '\n    private static final Map<BlockPos, Long> LAST_DRAW_LOG_NANOS')
        replace_once(renderer, 'debugDraw(state);',
                     'debugDraw(state);\n        com.nstut.simplyscreens.testing.visual.VisualClient.submittedTile(state.facing, state.width, state.height, state.blockPos.getX() + state.anchorOffsetX, state.blockPos.getY() + state.anchorOffsetY, state.blockPos.getZ() + state.anchorOffsetZ, state.blockPos.getX(), state.blockPos.getY(), state.blockPos.getZ());')
        return
    if variant != 'see-through':
        raise ValueError('Unknown visual variant: ' + variant)
    path = stage / mutable_paths()[0]
    text = path.read_text()
    old = 'new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true,\n                    DEPTH_BIAS_FACTOR, DEPTH_BIAS_UNITS)'
    if text.count(old) != 1:
        raise RuntimeError('26.2 visual depth-state mutation anchor changed')
    replacement = 'new DepthStencilState(CompareOp.ALWAYS_PASS, false, 0.0F, 0.0F)'
    path.write_text(text.replace(old, replacement))
