package com.nstut.simplyscreens.client.renderers;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Screen image render state: vanilla text polygon depth bias plus vanilla view-Z layering. */
public final class ScreenRenderTypes {
    private static final Map<Identifier, RenderType> TYPES = new ConcurrentHashMap<>();

    private ScreenRenderTypes() {}

    public static RenderType textPolygonOffset(Identifier texture) {
        return TYPES.computeIfAbsent(texture, ScreenRenderTypes::create);
    }

    private static RenderType create(Identifier texture) {
        RenderSetup setup = RenderSetup.builder(RenderPipelines.TEXT_POLYGON_OFFSET)
                .withTexture("Sampler0", texture)
                .useLightmap()
                .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .createRenderSetup();
        return RenderType.create("simply_screens_screen", setup);
    }
}
