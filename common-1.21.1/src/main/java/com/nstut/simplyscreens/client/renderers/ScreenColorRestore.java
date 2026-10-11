package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.nstut.simplyscreens.client.compat.ShaderPackState;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;

/** Replaces opaque image samples after shaderpack finalization, retaining the world's bloom contribution. */
public final class ScreenColorRestore {
    private static final List<Quad> FRAME = new ArrayList<>();
    private static ShaderInstance shader;
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(256));
    private ScreenColorRestore() {}

    public static void setShader(ShaderInstance value) { shader = value; }
    public static ShaderInstance getShader() { return shader; }
    public static void beginFrame() { FRAME.clear(); }

    public static void capture(ResourceLocation texture, PoseStack.Pose pose,
                               float minX, float minY, float maxX, float maxY,
                               float minU, float minV, float maxU, float maxV) {
        if (shader == null || !ShaderPackState.shouldCapture()) return;
        // Copy while the world matrices are current. Never retain mutable PoseStack state.
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        FRAME.add(new Quad(texture, projection, modelView, new Matrix4f(pose.pose()), minX, minY, maxX, maxY, minU, minV, maxU, maxV));
    }

    /** Called immediately after LevelRenderer returns, before hand rendering clears world depth. */
    public static void finishWorld() {
        ShaderInstance previousShader = RenderSystem.getShader();
        int previousTexture = RenderSystem.getShaderTexture(0);
        try {
            if (shader == null || !ShaderPackState.shouldCapture()) return;
            for (Quad quad : FRAME) {
                shader.safeGetUniform("ScreenProjection").set(quad.projection);
                shader.safeGetUniform("ScreenModelView").set(quad.modelView);
                RenderType type = ScreenColorRenderTypes.forTexture(quad.texture);
                VertexConsumer vertices = BUFFERS.getBuffer(type);
                vertex(vertices, quad.pose, quad.minX, quad.maxY, quad.maxU, quad.minV);
                vertex(vertices, quad.pose, quad.maxX, quad.maxY, quad.minU, quad.minV);
                vertex(vertices, quad.pose, quad.maxX, quad.minY, quad.minU, quad.maxV);
                vertex(vertices, quad.pose, quad.minX, quad.minY, quad.maxU, quad.maxV);
                BUFFERS.endBatch(type);
            }
        } finally {
            FRAME.clear();
            RenderSystem.setShader(() -> previousShader);
            RenderSystem.setShaderTexture(0, previousTexture);
        }
    }

    private static void vertex(VertexConsumer target, Matrix4f pose, float x, float y, float u, float v) {
        target.addVertex(pose, x, y, 0).setUv(u, v);
    }
    private record Quad(ResourceLocation texture, Matrix4f projection, Matrix4f modelView, Matrix4f pose,
                        float minX, float minY, float maxX, float maxY,
                        float minU, float minV, float maxU, float maxV) {}
}
