package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Screen image render state: depth-only polygon bias; world geometry remains authoritative for occlusion. */
public final class ScreenRenderTypes {
    private static final float DEPTH_BIAS_FACTOR = -1.0F;
    private static final float DEPTH_BIAS_UNITS = -128.0F;
    private static final Map<Identifier, RenderType> TYPES = new ConcurrentHashMap<>();
    // NeoForge's supported copy builder also lets Iris inherit the vanilla text
    // world/shadow routing. A fresh custom pipeline is unknown to Iris and can
    // render black into its world buffers, removing the image's bloom source.
    static final RenderPipeline SCREEN_PIPELINE = RenderPipelines.TEXT_POLYGON_OFFSET.toBuilder()
            .withLocation(Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "pipeline/screen"))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true,
                    DEPTH_BIAS_FACTOR, DEPTH_BIAS_UNITS))
            .build();

    private ScreenRenderTypes() {}

    public static RenderType textPolygonOffset(Identifier texture) {
        return TYPES.computeIfAbsent(texture, ScreenRenderTypes::create);
    }

    private static RenderType create(Identifier texture) {
        RenderSetup setup = RenderSetup.builder(SCREEN_PIPELINE)
                .withTexture("Sampler0", texture)
                .useLightmap()
                .sortOnUpload()
                .createRenderSetup();
        return RenderType.create("simply_screens_screen", setup);
    }
}
