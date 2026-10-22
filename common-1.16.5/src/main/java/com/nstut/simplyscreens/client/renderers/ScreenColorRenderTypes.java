package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
/** Native 1.16 fixed-function state, never bypassing world depth. */
final class ScreenColorRenderTypes extends RenderStateShard {
    private static final LayeringStateShard BIAS = new LayeringStateShard("screen_color_depth_bias",
            () -> { RenderSystem.polygonOffset(-2F,-32F); RenderSystem.enablePolygonOffset(); },
            () -> { RenderSystem.polygonOffset(0,0); RenderSystem.disablePolygonOffset(); });
    private ScreenColorRenderTypes() { super("screen_color_restore", () -> {}, () -> {}); }
    private static final java.util.Map<ResourceLocation,RenderType> WORLD = new java.util.concurrent.ConcurrentHashMap<>();
    static RenderType world(ResourceLocation texture) {
        return WORLD.computeIfAbsent(texture,key -> RenderType.create("simplyscreens_world",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,7,256,false,true,
            RenderType.CompositeState.builder().setTextureState(new TextureStateShard(key,false,false))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setLightmapState(LIGHTMAP)
                .setCullState(NO_CULL).setDepthTestState(LEQUAL_DEPTH_TEST)
                .setLayeringState(POLYGON_OFFSET_LAYERING).createCompositeState(false)));
    }
    static RenderType forTexture(ResourceLocation texture) {
        return RenderType.create("screen_color_restore", DefaultVertexFormat.POSITION_TEX, 7, 256, false, false,
                RenderType.CompositeState.builder().setTextureState(new TextureStateShard(texture,false,false))
                .setAlphaState(new AlphaStateShard(0.99999F)).setDepthTestState(LEQUAL_DEPTH_TEST)
                .setWriteMaskState(COLOR_WRITE).setTransparencyState(NO_TRANSPARENCY)
                .setLayeringState(BIAS).createCompositeState(false));
    }
}
