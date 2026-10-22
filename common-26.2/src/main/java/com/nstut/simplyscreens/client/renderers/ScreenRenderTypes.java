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
    private static final float DEPTH_BIAS_FACTOR = 1.0F;
    private static final float DEPTH_BIAS_UNITS = 128.0F;
    private static final Map<Identifier, RenderType> TYPES = new ConcurrentHashMap<>();
    // 26.2 uses reversed Z (clear=0), so positive bias moves toward the viewer.
    // Preserve vanilla text shaders, bind groups, defines, and vertex routing.
    public static final RenderPipeline SCREEN_PIPELINE = copyPipeline(RenderPipelines.TEXT_POLYGON_OFFSET)
            .withLocation(Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "pipeline/screen"))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true,
                    DEPTH_BIAS_FACTOR, DEPTH_BIAS_UNITS))
            .build();

    private ScreenRenderTypes() {}

    // Copy through the public backend-neutral API. No NeoForge-only builder or raw GL.
    private static RenderPipeline.Builder copyPipeline(RenderPipeline source) {
        RenderPipeline.Builder result = RenderPipeline.builder()
                .withVertexShader(source.getVertexShader()).withFragmentShader(source.getFragmentShader())
                .withPrimitiveTopology(source.getPrimitiveTopology()).withCull(source.isCull())
                .withPolygonMode(source.getPolygonMode()).withColorTargetState(source.getColorTargetState());
        source.getBindGroupLayouts().forEach(result::withBindGroupLayout);
        source.getShaderDefines().flags().forEach(result::withShaderDefine);
        source.getShaderDefines().values().forEach((name, value) -> {
            try { result.withShaderDefine(name, Integer.parseInt(value)); }
            catch (NumberFormatException ignored) { result.withShaderDefine(name, Float.parseFloat(value)); }
        });
        for (int i = 0; i < source.getVertexFormatBindings().length; i++)
            if (source.getVertexFormatBinding(i) != null) result.withVertexBinding(i, source.getVertexFormatBinding(i));
        return result;
    }

    public static RenderType textPolygonOffset(Identifier texture) {
        return TYPES.computeIfAbsent(texture, ScreenRenderTypes::create);
    }

    private static RenderType create(Identifier texture) {
        RenderSetup setup = RenderSetup.builder(SCREEN_PIPELINE)
                .withTexture("Sampler0", texture)
                .useLightmap()
                .sortOnUpload()
                .createRenderSetup();
        return com.nstut.simplyscreens.client.mixin.RenderTypeAccessor.simplyscreens$create("simply_screens_screen", setup);
    }
}
