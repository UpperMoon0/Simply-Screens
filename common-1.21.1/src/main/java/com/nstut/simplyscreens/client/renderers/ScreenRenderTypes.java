package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4fStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Screen image render state: vanilla text polygon offset plus vanilla view-Z layering. */
public final class ScreenRenderTypes extends RenderStateShard {
    private static final float VIEW_SCALE = 0.99975586F;
    private static final Map<ResourceLocation, RenderType> TYPES = new ConcurrentHashMap<>();
    private static final LayeringStateShard SCREEN_LAYERING = new LayeringStateShard(
            "simply_screens_polygon_view_offset",
            () -> {
                RenderSystem.polygonOffset(-1.0F, -10.0F);
                RenderSystem.enablePolygonOffset();
                Matrix4fStack modelView = RenderSystem.getModelViewStack();
                modelView.pushMatrix();
                modelView.scale(VIEW_SCALE, VIEW_SCALE, VIEW_SCALE);
                RenderSystem.applyModelViewMatrix();
            },
            () -> {
                Matrix4fStack modelView = RenderSystem.getModelViewStack();
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
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
                        .setLayeringState(SCREEN_LAYERING)
                        .createCompositeState(false));
    }
}
