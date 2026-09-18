package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nstut.simplyscreens.Config;
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

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity, ScreenBlockEntityRenderState> {
    private static final int SCREEN_SUBMIT_ORDER = 1;
    private static final int FULL_BRIGHTNESS = 15728880;
    private static final float BASE_OFFSET = 0.501f;
    private static final Map<BlockPos, Long> LAST_DRAW_LOG_NANOS = new ConcurrentHashMap<>();
    private static final FrameRenderClaims<Object> FRAME_RENDER_CLAIMS = new FrameRenderClaims<>();

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** Clears logical-screen ownership before each rendered frame. */
    public static void beginRenderFrame() {
        FRAME_RENDER_CLAIMS.clearValues();
    }

    /** Drops render claims belonging to an unloaded client level. */
    public static void clearLevel(Object levelIdentity) {
        FRAME_RENDER_CLAIMS.removeLevel(levelIdentity);
    }

    /** Releases level identities when the client leaves a world. */
    public static void clearCaches() {
        FRAME_RENDER_CLAIMS.clear();
        LAST_DRAW_LOG_NANOS.clear();
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
        state.levelIdentity = entity.getLevel();
        state.anchorKey = anchorPos == null ? 0L : anchorPos.asLong();
        state.visible = state.visible && state.levelIdentity != null;
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
    }

    @Override
    public void submit(ScreenBlockEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        // The full logical screen must always be submitted from the anchor tile.
        // Letting whichever tile renders first own the frame changes the PoseStack
        // origin across frames and can perturb queued custom geometry on 26.1.2.
        if (!state.visible || state.texture == null
                || state.anchorOffsetX != 0 || state.anchorOffsetY != 0 || state.anchorOffsetZ != 0
                || !FRAME_RENDER_CLAIMS.claim(state.levelIdentity, state.anchorKey)) return;
        debugDraw(state);
        poseStack.pushPose();
        poseStack.translate(state.anchorOffsetX, state.anchorOffsetY, state.anchorOffsetZ);
        poseStack.translate(0.5, 0.5, 0.5);
        applyFacingRotation(poseStack, state.facing);
        poseStack.translate(0, 0, state.facing == Direction.NORTH || state.facing == Direction.SOUTH ? -BASE_OFFSET : BASE_OFFSET);
        poseStack.translate(-(state.width - 1) / 2f, (state.height - 1) / 2f, 0);
        collector.order(SCREEN_SUBMIT_ORDER).submitCustomGeometry(poseStack, ScreenRenderTypes.textPolygonOffset(state.texture),
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
        float minX = -state.scaleX * 0.5f;
        float maxX = state.scaleX * 0.5f;
        float minY = -state.scaleY * 0.5f;
        float maxY = state.scaleY * 0.5f;
        vertex(consumer, pose, minX, maxY, 1.0f, 0.0f);
        vertex(consumer, pose, maxX, maxY, 0.0f, 0.0f);
        vertex(consumer, pose, maxX, minY, 0.0f, 1.0f);
        vertex(consumer, pose, minX, minY, 1.0f, 1.0f);
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

    static final class FrameRenderClaims<L> {
        private final Map<L, Set<Long>> levels = new IdentityHashMap<>();

        boolean claim(L levelIdentity, long anchor) {
            return levels.computeIfAbsent(levelIdentity, ignored -> new HashSet<>()).add(anchor);
        }

        void clearValues() {
            levels.values().forEach(Set::clear);
        }

        void removeLevel(L levelIdentity) {
            levels.remove(levelIdentity);
        }

        void clear() {
            levels.clear();
        }
    }

}
