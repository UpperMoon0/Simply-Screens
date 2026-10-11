package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.client.compat.ShaderPackState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Restores opaque source samples after Iris finalization, retaining the original world's bloom. */
public final class ScreenColorRestore {
    // Register with NeoForge so Minecraft recompiles these resource-backed shaders on every reload.
    // GPU vertex/index buffers and dynamic uniform storage are owned by RenderSystem, not this class.
    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "pipeline/screen_color_restore"))
            .withVertexShader(Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "core/screen_color_restore"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "core/screen_color_restore"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withSampler("Sampler0")
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
            .withColorTargetState(ColorTargetState.DEFAULT)
            // Match the existing image's polygon-offset depth; never change the world's depth.
            // 26.1.2 and Iris 1.11.4 use forward Z (IrisApi.isReverseZDuringShaders() is false).
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false, -1.0F, -128.0F))
            .build();
    private static final List<Quad> FRAME = new ArrayList<>();

    private ScreenColorRestore() {}

    public static void beginFrame() {
        FRAME.clear();
    }

    /** Called by the actual geometry callback, not the earlier render-state extraction/submission. */
    public static void capture(Identifier texture, PoseStack.Pose pose,
                               float minX, float minY, float maxX, float maxY,
                               float minU, float minV, float maxU, float maxV) {
        if (!ShaderPackState.shouldCapture()) return;
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        if (projection == null) return;
        // The world projection UBO is frame-local and consumed before GameRenderer switches to hand
        // projection. Copy the CPU matrices now: neither PoseStack nor extracted state may be retained.
        FRAME.add(new Quad(texture, snapshotTransforms(RenderSystem.getModelViewMatrix(), pose.pose()), projection,
                minX, minY, maxX, maxY, minU, minV, maxU, maxV));
    }

    static Transforms snapshotTransforms(Matrix4fc modelView, Matrix4fc pose) {
        return new Transforms(new Matrix4f(modelView), new Matrix4f(pose));
    }

    /** Runs after LevelRenderer returns and before GameRenderer clears the shared depth for hands. */
    public static void finishWorld() {
        try {
            if (FRAME.isEmpty() || !ShaderPackState.shouldCapture()) return;
            Minecraft minecraft = Minecraft.getInstance();
            RenderTarget target = minecraft.getMainRenderTarget();
            if (target.getColorTextureView() == null || target.getDepthTextureView() == null) return;
            try (ByteBufferBuilder storage = new ByteBufferBuilder(256)) {
                for (Quad quad : FRAME) draw(minecraft, target, storage, quad);
            }
        } finally {
            // Never retain textures, projection slices, or geometry across frames/reloads/world changes.
            FRAME.clear();
        }
    }

    private static void draw(Minecraft minecraft, RenderTarget target, ByteBufferBuilder storage, Quad quad) {
        BufferBuilder vertices = new BufferBuilder(storage, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // Match the text pass's arithmetic order exactly: pose on CPU, model-view/projection on GPU.
        // Pre-combining the matrices can round depth differently on far or steeply angled screens.
        vertex(vertices, quad.transforms.pose, quad.minX, quad.maxY, quad.maxU, quad.minV);
        vertex(vertices, quad.transforms.pose, quad.maxX, quad.maxY, quad.minU, quad.minV);
        vertex(vertices, quad.transforms.pose, quad.maxX, quad.minY, quad.minU, quad.maxV);
        vertex(vertices, quad.transforms.pose, quad.minX, quad.minY, quad.maxU, quad.maxV);
        try (MeshData mesh = vertices.buildOrThrow()) {
            GpuBuffer vertexBuffer = DefaultVertexFormat.POSITION_TEX.uploadImmediateVertexBuffer(mesh.vertexBuffer());
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            GpuBuffer indexBuffer = indices.getBuffer(mesh.drawState().indexCount());
            GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(
                    quad.transforms.modelView, new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
            // Resolve the current texture each time; resource reload may replace its GPU object.
            AbstractTexture texture = minecraft.getTextureManager().getTexture(quad.texture);
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Simply Screens source colors", target.getColorTextureView(), OptionalInt.empty(),
                    target.getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(PIPELINE);
                pass.setUniform("Projection", quad.projection);
                pass.setUniform("DynamicTransforms", transform);
                pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
                pass.setVertexBuffer(0, vertexBuffer);
                pass.setIndexBuffer(indexBuffer, indices.type());
                pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
            }
        }
    }

    private static void vertex(VertexConsumer target, Matrix4fc pose, float x, float y, float u, float v) {
        target.addVertex(pose, x, y, 0).setUv(u, v);
    }

    record Transforms(Matrix4f modelView, Matrix4f pose) {}

    private record Quad(Identifier texture, Transforms transforms, GpuBufferSlice projection,
                        float minX, float minY, float maxX, float maxY,
                        float minU, float minV, float maxU, float maxV) {}
}
