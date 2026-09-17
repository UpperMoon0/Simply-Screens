package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.ScreenTileLayout;
import com.nstut.simplyscreens.ScreenVisibility;
import com.nstut.simplyscreens.SimplyScreens;
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
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity, ScreenBlockEntityRenderState> {
    private static final int FULL_BRIGHTNESS = 15728880;
    private static final float BASE_OFFSET = 0.501f;
    private static final Map<BlockPos, Long> LAST_DRAW_LOG_NANOS = new ConcurrentHashMap<>();

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public ScreenBlockEntityRenderState createRenderState() {
        return new ScreenBlockEntityRenderState();
    }

    @Override
    public void extractRenderState(ScreenBlockEntity entity, ScreenBlockEntityRenderState state, float partialTicks,
                                   Vec3 cameraPosition, ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        ScreenBlockEntity anchor = entity.isAnchor() ? entity : entity.getAnchorEntity();
        ScreenBlockEntity renderData = anchor != null ? anchor : entity;
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTicks, cameraPosition, breakProgress);
        UUID imageId = renderData.getResolvedImageId();
        state.visible = imageId != null;
        state.facing = entity.getBlockState().hasProperty(ScreenBlock.FACING)
                ? entity.getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        state.width = renderData.getScreenWidth();
        state.height = renderData.getScreenHeight();
        BlockPos anchorPos = entity.getAnchorPos();
        state.visible = state.visible && anchorPos != null;
        state.anchorOffsetX = anchorPos == null ? 0 : anchorPos.getX() - entity.getBlockPos().getX();
        state.anchorOffsetY = anchorPos == null ? 0 : anchorPos.getY() - entity.getBlockPos().getY();
        state.anchorOffsetZ = anchorPos == null ? 0 : anchorPos.getZ() - entity.getBlockPos().getZ();
        state.texture = imageId == null ? null : ClientImageManager.getTextureLocation(imageId);
        state.scaleX = state.width;
        state.scaleY = state.height;
        if (imageId != null && renderData.isMaintainAspectRatio()) {
            DynamicTexture texture = ClientImageManager.getImageTexture(imageId);
            NativeImage image = texture == null ? null : texture.getPixels();
            if (image != null) {
                float imageAspect = (float) image.getWidth() / image.getHeight();
                float screenAspect = (float) state.width / state.height;
                if (imageAspect > screenAspect) state.scaleY = state.width / imageAspect;
                else state.scaleX = state.height * imageAspect;
            }
        }
        ScreenTileLayout.Tile tile = calculateTile(entity, renderData, state.facing, state.scaleX, state.scaleY);
        state.visible = state.visible && !tile.isEmpty();
        state.tileMinX = tile.minX();
        state.tileMaxX = tile.maxX();
        state.tileMinY = tile.minY();
        state.tileMaxY = tile.maxY();
        state.tileMinU = tile.minU();
        state.tileMaxU = tile.maxU();
        state.tileMinV = tile.minV();
        state.tileMaxV = tile.maxV();
    }

    @Override
    public void submit(ScreenBlockEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.visible || state.texture == null) return;
        debugDraw(state);
        poseStack.pushPose();
        poseStack.translate(state.anchorOffsetX, state.anchorOffsetY, state.anchorOffsetZ);
        poseStack.translate(0.5, 0.5, 0.5);
        applyFacingRotation(poseStack, state.facing);
        poseStack.translate(0, 0, state.facing == Direction.NORTH || state.facing == Direction.SOUTH ? -BASE_OFFSET : BASE_OFFSET);
        poseStack.translate(-(state.width - 1) / 2f, (state.height - 1) / 2f, 0);
        collector.submitCustomGeometry(poseStack, RenderTypes.text(state.texture),
                (pose, consumer) -> buildTexturedQuad(consumer, pose, state));
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

    private static void buildTexturedQuad(VertexConsumer consumer, PoseStack.Pose pose, ScreenBlockEntityRenderState state) {
        vertex(consumer, pose, state.tileMinX, state.tileMaxY, state.tileMaxU, state.tileMinV);
        vertex(consumer, pose, state.tileMaxX, state.tileMaxY, state.tileMinU, state.tileMinV);
        vertex(consumer, pose, state.tileMaxX, state.tileMinY, state.tileMinU, state.tileMaxV);
        vertex(consumer, pose, state.tileMinX, state.tileMinY, state.tileMaxU, state.tileMaxV);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float u, float v) {
        consumer.addVertex(pose, x, y, 0).setColor(-1).setUv(u, v)
                .setLight(FULL_BRIGHTNESS).setNormal(pose, 0, 0, 1);
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public int getViewDistance() {
        return Config.VIEW_DISTANCE;
    }

    @Override
    public boolean shouldRender(ScreenBlockEntity entity, Vec3 cameraPosition) {
        BlockPos anchor = entity.getAnchorPos();
        if (anchor == null) return false;
        BlockPos farCorner = getFarCorner(entity);
        return ScreenVisibility.isWithinDistance(
                cameraPosition.x, cameraPosition.y, cameraPosition.z,
                anchor.getX(), anchor.getY(), anchor.getZ(),
                farCorner.getX(), farCorner.getY(), farCorner.getZ(),
                getViewDistance());
    }

    private static BlockPos getFarCorner(ScreenBlockEntity entity) {
        Direction facing = entity.getBlockState().hasProperty(ScreenBlock.FACING)
                ? entity.getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        Direction widthDirection = switch (facing) {
            case NORTH, UP, DOWN -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
        };
        Direction heightDirection = facing.getAxis().isHorizontal()
                ? Direction.UP : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        return entity.getAnchorPos()
                .relative(widthDirection, entity.getScreenWidth() - 1)
                .relative(heightDirection, entity.getScreenHeight() - 1);
    }

    private static void debugDraw(ScreenBlockEntityRenderState state) {
        if (!Config.DEBUG_RENDERING) return;
        BlockPos owner = state.blockPos;
        long now = System.nanoTime();
        Long lastLog = LAST_DRAW_LOG_NANOS.get(owner);
        if (lastLog != null && now - lastLog < 1_000_000_000L) return;
        LAST_DRAW_LOG_NANOS.put(owner, now);
        SimplyScreens.LOGGER.info("Screen render draw owner={} offset=({}, {}, {}) texture={} size={}x{} facing={} geometrySubmitted=true",
                owner, state.anchorOffsetX, state.anchorOffsetY, state.anchorOffsetZ,
                state.texture, state.width, state.height, state.facing);
    }

    private static Direction getWidthDirection(Direction facing) {
        return switch (facing) {
            case NORTH, UP, DOWN -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
        };
    }

    private static ScreenTileLayout.Tile calculateTile(ScreenBlockEntity entity, ScreenBlockEntity anchor,
                                                        Direction facing, float imageWidth, float imageHeight) {
        Direction width = getWidthDirection(facing);
        Direction height = facing.getAxis().isHorizontal()
                ? Direction.UP : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        BlockPos current = entity.getBlockPos();
        BlockPos origin = entity.getAnchorPos();
        int dx = current.getX() - origin.getX();
        int dy = current.getY() - origin.getY();
        int dz = current.getZ() - origin.getZ();
        int widthIndex = dx * width.getStepX() + dy * width.getStepY() + dz * width.getStepZ();
        int heightIndex = dx * height.getStepX() + dy * height.getStepY() + dz * height.getStepZ();
        return ScreenTileLayout.calculate(anchor.getScreenWidth(), anchor.getScreenHeight(),
                widthIndex, heightIndex, imageWidth, imageHeight);
    }

}
