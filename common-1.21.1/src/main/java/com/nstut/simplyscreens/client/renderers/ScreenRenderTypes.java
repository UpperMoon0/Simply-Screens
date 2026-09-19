package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Screen image render state: stronger depth-only polygon bias with no projected-geometry shift. */
public final class ScreenRenderTypes extends RenderStateShard {
    private static final float DEPTH_BIAS_FACTOR = -1.0F;
    private static final float DEPTH_BIAS_UNITS = -16.0F;
    private static final Map<ResourceLocation, RenderType> TYPES = new ConcurrentHashMap<>();
    private static final LayeringStateShard SCREEN_LAYERING = new LayeringStateShard(
            "simply_screens_polygon_offset",
            () -> {
                RenderSystem.polygonOffset(DEPTH_BIAS_FACTOR, DEPTH_BIAS_UNITS);
                RenderSystem.enablePolygonOffset();
            },
            () -> {
                RenderSystem.polygonOffset(0.0F, 0.0F);
                RenderSystem.disablePolygonOffset();
            });

    private ScreenRenderTypes() {
        super("simply_screens_screen_render_types", () -> {}, () -> {});
    }

    public static RenderType textPolygonOffset(ResourceLocation texture) {
        return TYPES.computeIfAbsent(texture, ScreenRenderTypes::create);
    }

    private static RenderType create(ResourceLocation texture) {
        return RenderType.create(
                "simply_screens_screen",
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                VertexFormat.Mode.QUADS,
                256,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_TEXT_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setLightmapState(LIGHTMAP)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setLayeringState(SCREEN_LAYERING)
                        .createCompositeState(false));
    }
}
