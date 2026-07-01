package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.ScreenRenderProxy;
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
    private static long lastDebugLogNanos;
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
        if (!isChunkRepresentative(entity)) {
            BlockEntityRenderer.super.extractRenderState(entity, state, partialTicks, cameraPosition, breakProgress);
            state.visible = false;
            return;
        }
        ScreenBlockEntity anchor = entity;
        BlockEntityRenderer.super.extractRenderState(anchor, state, partialTicks, cameraPosition, breakProgress);
        UUID imageId = anchor.getResolvedImageId();
        state.visible = imageId != null;
        state.facing = anchor.getBlockState().hasProperty(ScreenBlock.FACING)
                ? anchor.getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        state.width = anchor.getScreenWidth();
        state.height = anchor.getScreenHeight();
        BlockPos anchorPos = entity.getAnchorPos();
        BlockPos entityPos = entity.getBlockPos();
        // submit() starts at the elected cell; this offset restores the logical anchor coordinate space.
        state.anchorOffsetX = anchorPos == null ? 0 : anchorPos.getX() - entityPos.getX();
        state.anchorOffsetY = anchorPos == null ? 0 : anchorPos.getY() - entityPos.getY();
        state.anchorOffsetZ = anchorPos == null ? 0 : anchorPos.getZ() - entityPos.getZ();
        state.texture = imageId == null ? null : ClientImageManager.getTextureLocation(imageId);
        state.scaleX = state.width;
        state.scaleY = state.height;
        if (imageId != null && anchor.isMaintainAspectRatio()) {
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
        debugDraw(state);
        poseStack.pushPose();
        poseStack.translate(state.anchorOffsetX, state.anchorOffsetY, state.anchorOffsetZ);
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
    public boolean shouldRenderOffScreen() {
        return true;
    }

    @Override
    public int getViewDistance() {
        return Config.VIEW_DISTANCE;
    }

    @Override
    public boolean shouldRender(ScreenBlockEntity entity, Vec3 cameraPosition) {
        // Keep every loaded chunk eligible; extractRenderState() deduplicates geometry within each chunk.
        isRenderOwner(entity, cameraPosition); // Retain proxy diagnostics while investigating visibility.
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

    private static boolean isRenderOwner(ScreenBlockEntity entity, Vec3 camera) {
        if (entity.getAnchorPos() == null) return false;
        Direction facing = entity.getBlockState().hasProperty(ScreenBlock.FACING)
                ? entity.getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        Direction width = getWidthDirection(facing);
        Direction height = facing.getAxis().isHorizontal()
                ? Direction.UP : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        BlockPos anchor = entity.getAnchorPos();
        ScreenRenderProxy.Position proxy = ScreenRenderProxy.nearestCell(
                camera.x, camera.y, camera.z,
                anchor.getX(), anchor.getY(), anchor.getZ(),
                width.getStepX(), width.getStepY(), width.getStepZ(),
                height.getStepX(), height.getStepY(), height.getStepZ(),
                entity.getScreenWidth(), entity.getScreenHeight());
        BlockPos current = entity.getBlockPos();
        debugOwnership(entity, camera, facing, proxy);
        // Useful breakpoint: proxy must equal exactly one loaded cell for the screen to be submitted.
        return current.getX() == proxy.x() && current.getY() == proxy.y() && current.getZ() == proxy.z();
    }

    private static void debugOwnership(ScreenBlockEntity entity, Vec3 camera, Direction facing,
                                       ScreenRenderProxy.Position proxy) {
        if (!Config.DEBUG_RENDERING || System.nanoTime() - lastDebugLogNanos < 1_000_000_000L) return;
        lastDebugLogNanos = System.nanoTime();
        BlockPos anchor = entity.getAnchorPos();
        if (anchor == null || entity.getLevel() == null) return;
        BlockPos proxyPos = new BlockPos(proxy.x(), proxy.y(), proxy.z());
        var proxyEntity = entity.getLevel().getBlockEntity(proxyPos);
        var anchorEntity = entity.getLevel().getBlockEntity(anchor);
        BlockPos far = getFarCorner(entity);
        boolean boundsVisible = ScreenVisibility.isWithinDistance(camera.x, camera.y, camera.z,
                anchor.getX(), anchor.getY(), anchor.getZ(), far.getX(), far.getY(), far.getZ(), Config.VIEW_DISTANCE);
        SimplyScreens.LOGGER.info("Screen render debug camera={} current={} anchor={} anchorLoaded={} proxy={} proxyLoaded={} proxyAnchor={} proxyImage={} size={}x{} facing={} boundsVisible={}",
                camera, entity.getBlockPos(), anchor, anchorEntity instanceof ScreenBlockEntity, proxyPos,
                proxyEntity instanceof ScreenBlockEntity,
                proxyEntity instanceof ScreenBlockEntity screen ? screen.getAnchorPos() : null,
                proxyEntity instanceof ScreenBlockEntity screen ? screen.getResolvedImageId() : null,
                entity.getScreenWidth(), entity.getScreenHeight(), facing, boundsVisible);
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

    private static boolean isChunkRepresentative(ScreenBlockEntity entity) {
        BlockPos anchor = entity.getAnchorPos();
        if (anchor == null) return false;
        Direction facing = entity.getBlockState().hasProperty(ScreenBlock.FACING)
                ? entity.getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        Direction width = getWidthDirection(facing);
        Direction height = facing.getAxis().isHorizontal()
                ? Direction.UP : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        BlockPos current = entity.getBlockPos();
        int dx = current.getX() - anchor.getX();
        int dy = current.getY() - anchor.getY();
        int dz = current.getZ() - anchor.getZ();
        int widthIndex = dx * width.getStepX() + dy * width.getStepY() + dz * width.getStepZ();
        int heightIndex = dx * height.getStepX() + dy * height.getStepY() + dz * height.getStepZ();
        if (widthIndex > 0 && sameChunk(current, current.relative(width.getOpposite()))) return false;
        return heightIndex <= 0 || !sameChunk(current, current.relative(height.getOpposite()));
    }

    private static boolean sameChunk(BlockPos first, BlockPos second) {
        return (first.getX() >> 4) == (second.getX() >> 4) && (first.getZ() >> 4) == (second.getZ() >> 4);
    }
}
