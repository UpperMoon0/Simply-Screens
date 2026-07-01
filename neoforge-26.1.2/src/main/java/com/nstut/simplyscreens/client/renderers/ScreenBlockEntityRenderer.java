package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.blocks.ScreenBlock;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity, ScreenBlockEntityRenderState> {
    private static final int FULL_BRIGHTNESS = 15728880;
    private static final float BASE_OFFSET = 0.501f;

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public ScreenBlockEntityRenderState createRenderState() {
        return new ScreenBlockEntityRenderState();
    }

    @Override
    public void extractRenderState(ScreenBlockEntity entity, ScreenBlockEntityRenderState state, float partialTicks,
                                   Vec3 cameraPosition, ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTicks, cameraPosition, breakProgress);
        UUID imageId = entity.getResolvedImageId();
        state.visible = entity.isAnchor() && imageId != null;
        state.facing = entity.getBlockState().hasProperty(ScreenBlock.FACING)
                ? entity.getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        state.width = entity.getScreenWidth();
        state.height = entity.getScreenHeight();
        state.texture = imageId == null ? null : ClientImageManager.getTextureLocation(imageId);
        state.scaleX = state.width;
        state.scaleY = state.height;
        if (imageId != null && entity.isMaintainAspectRatio()) {
            DynamicTexture texture = ClientImageManager.getImageTexture(imageId);
            NativeImage image = texture == null ? null : texture.getPixels();
            if (image != null) {
                float imageAspect = (float) image.getWidth() / image.getHeight();
                float screenAspect = (float) state.width / state.height;
                if (imageAspect > screenAspect) state.scaleY = state.width / imageAspect;
                else state.scaleX = state.height * imageAspect;
            }
        }
    }

    @Override
    public void submit(ScreenBlockEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.visible || state.texture == null) return;
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        applyFacingRotation(poseStack, state.facing);
        poseStack.translate(0, 0, state.facing == Direction.NORTH || state.facing == Direction.SOUTH ? -BASE_OFFSET : BASE_OFFSET);
        poseStack.translate(-(state.width - 1) / 2f, (state.height - 1) / 2f, 0);
        poseStack.scale(state.scaleX, state.scaleY, 1);
        collector.submitCustomGeometry(poseStack, RenderTypes.text(state.texture),
                (pose, consumer) -> buildTexturedQuad(consumer, pose));
        poseStack.popPose();
    }

    private static void applyFacingRotation(PoseStack poseStack, Direction facing) {
        switch (facing) {
            case SOUTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180));
            case WEST -> { poseStack.mulPose(Axis.YP.rotationDegrees(270)); poseStack.scale(-1, 1, 1); }
            case EAST -> { poseStack.mulPose(Axis.YP.rotationDegrees(90)); poseStack.scale(-1, 1, 1); }
            case UP -> { poseStack.mulPose(Axis.XP.rotationDegrees(270)); poseStack.scale(1, -1, 1); }
            case DOWN -> { poseStack.mulPose(Axis.XP.rotationDegrees(90)); poseStack.scale(1, -1, 1); }
            default -> { }
        }
    }

    private static void buildTexturedQuad(VertexConsumer consumer, PoseStack.Pose pose) {
        vertex(consumer, pose, -.5f, .5f, 1, 0);
        vertex(consumer, pose, .5f, .5f, 0, 0);
        vertex(consumer, pose, .5f, -.5f, 0, 1);
        vertex(consumer, pose, -.5f, -.5f, 1, 1);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float u, float v) {
        consumer.addVertex(pose, x, y, 0).setColor(-1).setUv(u, v)
                .setLight(FULL_BRIGHTNESS).setNormal(pose, 0, 0, 1);
    }

    @Override
    public int getViewDistance() {
        return Config.VIEW_DISTANCE;
    }
}
