package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nstut.simplyscreens.SimplyScreens;
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
    private static final RenderPipeline SCREEN_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "pipeline/screen"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS)
            .withVertexShader("core/rendertype_text")
            .withFragmentShader("core/rendertype_text")
            .withSampler("Sampler0")
            .withSampler("Sampler2")
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
