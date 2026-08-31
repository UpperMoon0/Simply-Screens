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
import com.nstut.simplyscreens.client.compat.sable.ClientScreenSpatialResolver;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

public class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
    private static final int FULL_BRIGHTNESS = 15728880;
    private static final float BASE_OFFSET = 0.501f;
    private static final Map<BlockPos, Long> LAST_DRAW_LOG_NANOS = new ConcurrentHashMap<>();
    private static final FrameRenderClaims<Object> FRAME_RENDER_CLAIMS = new FrameRenderClaims<>();

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** Clears logical-screen ownership before each real world render pass. */
    public static void beginRenderFrame() {
        FRAME_RENDER_CLAIMS.clearValues();
    }

    /** Drops render claims belonging to a level the client just left. */
    public static void clearLevel(ClientLevel level) {
        FRAME_RENDER_CLAIMS.removeLevel(level);
    }

    /** Releases level identities when the client leaves a world. */
    public static void clearCaches() {
        FRAME_RENDER_CLAIMS.clear();
        LAST_DRAW_LOG_NANOS.clear();
    }

    @Override
    public void render(@NotNull ScreenBlockEntity blockEntity, float partialTicks, @NotNull PoseStack poseStack,
                       @NotNull MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BlockPos anchorPos = blockEntity.getAnchorPos();
        Object levelIdentity = blockEntity.getLevel();
        UUID localImageId = blockEntity.getResolvedImageId();
        if (anchorPos == null || levelIdentity == null || localImageId == null
                || !FRAME_RENDER_CLAIMS.claim(levelIdentity, anchorPos.asLong())) return;

        ScreenBlockEntity loadedAnchor = blockEntity.isAnchor() ? blockEntity : blockEntity.getAnchorEntity();
        ScreenBlockEntity renderData = loadedAnchor != null ? loadedAnchor : blockEntity;
        UUID imageId = renderData.getResolvedImageId();
        if (imageId == null) imageId = localImageId;

        ResourceLocation texture = ClientImageManager.getTextureLocation(imageId);
        DynamicTexture imageTexture = ClientImageManager.getImageTexture(imageId);
        if (texture == null || imageTexture == null) return;

        BlockState blockState = blockEntity.getBlockState();
        Direction facing = blockState.hasProperty(ScreenBlock.FACING)
                ? blockState.getValue(ScreenBlock.FACING) : Direction.NORTH;
        float displayWidth = renderData.getScreenWidth();
        float displayHeight = renderData.getScreenHeight();
        if (renderData.isMaintainAspectRatio()) {
            NativeImage image = imageTexture.getPixels();
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                displayWidth = 1.0f;
                displayHeight = 1.0f;
            } else {
                float imageAspect = (float) image.getWidth() / image.getHeight();
                float screenAspect = (float) renderData.getScreenWidth() / renderData.getScreenHeight();
                if (imageAspect > screenAspect) {
                    displayHeight = displayWidth / imageAspect;
                } else {
                    displayWidth = displayHeight * imageAspect;
                }
            }
        }

        poseStack.pushPose();
        try {
            if (!blockEntity.isAnchor()) {
                BlockPos currentPos = blockEntity.getBlockPos();
                poseStack.translate(
                        anchorPos.getX() - currentPos.getX(),
                        anchorPos.getY() - currentPos.getY(),
                        anchorPos.getZ() - currentPos.getZ());
            }
            prepareRenderingTransform(poseStack, renderData, facing);
            debugDraw(blockEntity, anchorPos, texture, facing);
            renderTextureQuad(texture, displayWidth, displayHeight, poseStack, bufferSource, packedOverlay);
        } finally {
            poseStack.popPose();
        }
    }

    @Override
    public boolean shouldRenderOffScreen(ScreenBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return Config.VIEW_DISTANCE;
    }

    @Override
    public boolean shouldRender(ScreenBlockEntity blockEntity, Vec3 cameraPosition) {
        BlockPos anchor = blockEntity.getAnchorPos();
        if (anchor == null) return false;
        if (blockEntity.getLevel() instanceof ClientLevel clientLevel) {
            Boolean sableVisibility = ClientScreenSpatialResolver.isWithinRenderDistance(
                    clientLevel, blockEntity, anchor, getViewDistance());
            if (sableVisibility != null) return sableVisibility;
        }
        BlockPos farCorner = getFarCorner(blockEntity);
        return ScreenVisibility.isWithinDistance(
                cameraPosition.x, cameraPosition.y, cameraPosition.z,
                anchor.getX(), anchor.getY(), anchor.getZ(),
                farCorner.getX(), farCorner.getY(), farCorner.getZ(),
                getViewDistance());
    }

    private static BlockPos getFarCorner(ScreenBlockEntity blockEntity) {
        Direction facing = blockEntity.getBlockState().hasProperty(ScreenBlock.FACING)
                ? blockEntity.getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        Direction widthDirection = getWidthDirection(facing);
        Direction heightDirection = facing.getAxis().isHorizontal()
                ? Direction.UP : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        return blockEntity.getAnchorPos()
                .relative(widthDirection, blockEntity.getScreenWidth() - 1)
                .relative(heightDirection, blockEntity.getScreenHeight() - 1);
    }

    private static void debugDraw(ScreenBlockEntity owner, BlockPos anchor,
                                  ResourceLocation texture, Direction facing) {
        if (!Config.DEBUG_RENDERING) return;
        long now = System.nanoTime();
        Long lastLog = LAST_DRAW_LOG_NANOS.get(anchor);
        if (lastLog != null && now - lastLog < 1_000_000_000L) return;
        LAST_DRAW_LOG_NANOS.put(anchor, now);
        BlockPos ownerPos = owner.getBlockPos();
        SimplyScreens.LOGGER.info("Screen render draw owner={} anchor={} offset=({}, {}, {}) image={} texture={} size={}x{} facing={} geometrySubmitted=true",
                ownerPos, anchor,
                anchor.getX() - ownerPos.getX(),
                anchor.getY() - ownerPos.getY(),
                anchor.getZ() - ownerPos.getZ(),
                owner.getResolvedImageId(), texture, owner.getScreenWidth(), owner.getScreenHeight(), facing);
    }

    private static Direction getWidthDirection(Direction facing) {
        return switch (facing) {
            case NORTH, UP, DOWN -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
        };
    }

    private static void prepareRenderingTransform(PoseStack poseStack, ScreenBlockEntity screen, Direction facing) {
        poseStack.translate(0.5, 0.5, 0.5);
        applyFacingRotation(poseStack, facing);
        poseStack.translate(0, 0, calculateFrontOffset(facing));
        poseStack.translate(-(screen.getScreenWidth() - 1) / 2f, (screen.getScreenHeight() - 1) / 2f, 0);
    }

    private static float calculateFrontOffset(Direction facing) {
        return switch (facing) {
            case NORTH, SOUTH -> -BASE_OFFSET;
            default -> BASE_OFFSET;
        };
    }

    private static void applyFacingRotation(PoseStack poseStack, Direction facing) {
        switch (facing) {
            case NORTH:
                break;
            case SOUTH:
                poseStack.mulPose(Axis.YP.rotationDegrees(180));
                break;
            case WEST:
                poseStack.mulPose(Axis.YP.rotationDegrees(270));
                poseStack.scale(-1, 1, 1);
                break;
            case EAST:
                poseStack.mulPose(Axis.YP.rotationDegrees(90));
                poseStack.scale(-1, 1, 1);
                break;
            case UP:
                poseStack.mulPose(Axis.XP.rotationDegrees(270));
                poseStack.scale(1, -1, 1);
                break;
            case DOWN:
                poseStack.mulPose(Axis.XP.rotationDegrees(90));
                poseStack.scale(1, -1, 1);
                break;
        }
    }

    private static void renderTextureQuad(ResourceLocation texture, float width, float height,
                                          PoseStack poseStack, MultiBufferSource bufferSource, int packedOverlay) {
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.text(texture));
        PoseStack.Pose pose = poseStack.last();
        float minX = -width * 0.5f;
        float maxX = width * 0.5f;
        float minY = -height * 0.5f;
        float maxY = height * 0.5f;

        addVertex(consumer, pose, packedOverlay, minX, maxY, 1.0f, 0.0f);
        addVertex(consumer, pose, packedOverlay, maxX, maxY, 0.0f, 0.0f);
        addVertex(consumer, pose, packedOverlay, maxX, minY, 0.0f, 1.0f);
        addVertex(consumer, pose, packedOverlay, minX, minY, 1.0f, 1.0f);
    }

    private static void addVertex(VertexConsumer consumer, PoseStack.Pose pose, int overlay,
                                  float x, float y, float u, float v) {
        consumer.addVertex(pose.pose(), x, y, 0)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(overlay)
                .setUv2(FULL_BRIGHTNESS & 0xFFFF, FULL_BRIGHTNESS >> 16)
                .setNormal(0, 0, 1);
    }

    static final class FrameRenderClaims<L> {
        private final Map<L, LongOpenHashSet> levels = new IdentityHashMap<>();

        boolean claim(L levelIdentity, long anchor) {
            return levels.computeIfAbsent(levelIdentity, ignored -> new LongOpenHashSet()).add(anchor);
        }

        void clearValues() {
            levels.values().forEach(LongOpenHashSet::clear);
        }

        void removeLevel(L levelIdentity) {
            levels.remove(levelIdentity);
        }

        void clear() {
            levels.clear();
        }
    }
}
