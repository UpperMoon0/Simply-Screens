package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** Private late-pass state. Depth tests are mandatory; this is never a see-through layer. */
final class ScreenColorRenderTypes extends RenderStateShard {
    // One additional raster-pixel slope margin relative to the world image (-1/-16).
    // Shader TAA jitter and floating-point matrix rounding otherwise make replay fail
    // LEQUAL at oblique angles. This is bounded polygon offset, never depth bypass.
    private static final LayeringStateShard BIAS = new LayeringStateShard("screen_color_depth_bias",
            () -> { RenderSystem.polygonOffset(-2.0F, -32.0F); RenderSystem.enablePolygonOffset(); },
            () -> { RenderSystem.polygonOffset(0.0F, 0.0F); RenderSystem.disablePolygonOffset(); });
    private ScreenColorRenderTypes() { super("screen_color_restore", () -> {}, () -> {}); }
    static RenderType forTexture(ResourceLocation texture) {
        return RenderType.create("screen_color_restore", DefaultVertexFormat.POSITION_TEX,
                VertexFormat.Mode.QUADS, 256, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(new ShaderStateShard(ScreenColorRestore::getShader))
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_WRITE)
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setLayeringState(BIAS)
                        .createCompositeState(false));
    }
}
