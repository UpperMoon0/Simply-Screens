package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.ScreenRenderProxy;
import com.nstut.simplyscreens.ScreenVisibility;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.blocks.ScreenBlock;
import com.nstut.simplyscreens.helpers.ClientImageManager;
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
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
    private static final int FULL_BRIGHTNESS = 15728880;
    private static final float BASE_OFFSET = 0.501f;
    private static long lastDebugLogNanos;
    private static final Map<BlockPos, Long> LAST_DRAW_LOG_NANOS = new ConcurrentHashMap<>();

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(@NotNull ScreenBlockEntity blockEntity, float partialTicks, @NotNull PoseStack poseStack,
                       @NotNull MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!isChunkRepresentative(blockEntity)) return;
        if (blockEntity.getResolvedImageId() == null) return;
        ScreenBlockEntity anchor = blockEntity;

        UUID imageId = anchor.getResolvedImageId();
        ResourceLocation texture = ClientImageManager.getTextureLocation(imageId);
        if (texture == null) return;

        if (!blockEntity.isAnchor()) {
            BlockPos anchorPos = blockEntity.getAnchorPos();
            if (anchorPos == null) return;
            BlockPos currentPos = blockEntity.getBlockPos();
            // The elected child renders in its own coordinate space, so move back to the logical anchor first.
            poseStack.translate(
                    anchorPos.getX() - currentPos.getX(),
                    anchorPos.getY() - currentPos.getY(),
                    anchorPos.getZ() - currentPos.getZ());
        }

        BlockState blockState = anchor.getBlockState();
        Direction facing = blockState.hasProperty(ScreenBlock.FACING) ?
            blockState.getValue(ScreenBlock.FACING) : Direction.NORTH;

        debugDraw(blockEntity, texture, facing);

        prepareRenderingTransform(poseStack, anchor, facing);
        renderTextureQuad(texture, poseStack, bufferSource, packedOverlay);
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
        // Keep every loaded chunk eligible; render() deduplicates geometry within each chunk.
        isRenderOwner(blockEntity, cameraPosition); // Retain proxy diagnostics while investigating visibility.
        BlockPos anchor = blockEntity.getAnchorPos();
        if (anchor == null) return false;
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
        Direction widthDirection = switch (facing) {
            case NORTH, UP, DOWN -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
        };
        Direction heightDirection = facing.getAxis().isHorizontal()
                ? Direction.UP : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        return blockEntity.getAnchorPos()
                .relative(widthDirection, blockEntity.getScreenWidth() - 1)
                .relative(heightDirection, blockEntity.getScreenHeight() - 1);
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

    private static void debugDraw(ScreenBlockEntity entity, ResourceLocation texture, Direction facing) {
        if (!Config.DEBUG_RENDERING) return;
        BlockPos owner = entity.getBlockPos();
        long now = System.nanoTime();
        Long lastLog = LAST_DRAW_LOG_NANOS.get(owner);
        if (lastLog != null && now - lastLog < 1_000_000_000L) return;
        LAST_DRAW_LOG_NANOS.put(owner, now);
        BlockPos anchor = entity.getAnchorPos();
        SimplyScreens.LOGGER.info("Screen render draw owner={} anchor={} offset=({}, {}, {}) image={} texture={} size={}x{} facing={} geometrySubmitted=true",
                owner, anchor,
                anchor == null ? 0 : anchor.getX() - owner.getX(),
                anchor == null ? 0 : anchor.getY() - owner.getY(),
                anchor == null ? 0 : anchor.getZ() - owner.getZ(),
                entity.getResolvedImageId(), texture, entity.getScreenWidth(), entity.getScreenHeight(), facing);
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

    private void prepareRenderingTransform(PoseStack poseStack, ScreenBlockEntity blockEntity, Direction facing) {
        poseStack.pushPose();

        // Center on block
        poseStack.translate(0.5, 0.5, 0.5);

        // Apply facing rotation
        applyFacingRotation(poseStack, facing);

        // Move to front face with direction-aware offset
        float frontOffset = calculateFrontOffset(facing);
        poseStack.translate(0, 0, frontOffset);

        // Adjust for screen structure size
        centerOnScreenStructure(poseStack, blockEntity, facing);

        // Apply aspect ratio scaling
        applyAspectRatioScaling(poseStack, blockEntity);
    }

    private float calculateFrontOffset(Direction facing) {
        return switch (facing) {
            case NORTH, SOUTH -> -BASE_OFFSET;
            default -> BASE_OFFSET;
        };
    }

    private void applyFacingRotation(PoseStack poseStack, Direction facing) {
        // This method applies additional rotations and flips based on the screen's facing direction.
        // This is an intentional and required feature for the screen to function as intended.
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

    private void centerOnScreenStructure(PoseStack poseStack, ScreenBlockEntity blockEntity, Direction facing) {
        float centerX;
        float centerY;

        if (facing.getAxis().isHorizontal()) {
            // Horizontal screen: width is along x or z, height is vertical (y-axis)
            centerX = -(blockEntity.getScreenWidth() - 1) / 2f;
            centerY = (blockEntity.getScreenHeight() - 1) / 2f;
        } else {
            // Vertical screens (UP/DOWN): width is x-axis, height is z-axis
            centerX = -(blockEntity.getScreenWidth() - 1) / 2f;
            centerY = (blockEntity.getScreenHeight() - 1) / 2f;
        }

        poseStack.translate(centerX, centerY, 0);
    }

    private void applyAspectRatioScaling(PoseStack poseStack, ScreenBlockEntity blockEntity) {
        // Get fresh values directly from block entity
        int width = blockEntity.getScreenWidth();
        int height = blockEntity.getScreenHeight();
        boolean maintainAspect = blockEntity.isMaintainAspectRatio();

        UUID imageId = blockEntity.getResolvedImageId();
        if (imageId == null) return;
        DynamicTexture texture = ClientImageManager.getImageTexture(imageId);
        if (texture == null) return;

        float[] scales = calculateScalingFactors(texture, width, height, maintainAspect);
        poseStack.scale(scales[0], scales[1], 1.0f);
    }

    private float[] calculateScalingFactors(DynamicTexture texture, int width, int height, boolean keepAspect) {
        if (!keepAspect) {
            return new float[]{width, height};
        }

        NativeImage image = texture.getPixels();
        if (image == null) return new float[]{1, 1};

        float imageAspect = (float) image.getWidth() / image.getHeight();
        float screenAspect = (float) width / height;

        return imageAspect > screenAspect ?
                new float[]{width, width / imageAspect} :
                new float[]{height * imageAspect, height};
    }

    private void renderTextureQuad(ResourceLocation texture, PoseStack poseStack,
                                   MultiBufferSource bufferSource, int packedOverlay) {
        VertexConsumer vertexBuffer = bufferSource.getBuffer(RenderType.text(texture));
        PoseStack.Pose pose = poseStack.last();

        buildTexturedQuad(vertexBuffer, pose, packedOverlay);
        poseStack.popPose();
    }

    private void buildTexturedQuad(VertexConsumer consumer, PoseStack.Pose pose, int overlay) {
        // The UV coordinates are intentionally flipped horizontally (U is swapped)
        // to ensure the image displays correctly on the screen. This is not a bug.
        // Top-right vertex
        consumer.addVertex(pose.pose(), -0.5f, 0.5f, 0)
                .setColor(255, 255, 255, 255)
                .setUv(1, 0)
                .setOverlay(overlay)
                .setUv2(FULL_BRIGHTNESS & 0xFFFF, FULL_BRIGHTNESS >> 16)
                .setNormal(0, 0, 1);

        // Top-left vertex
        consumer.addVertex(pose.pose(), 0.5f, 0.5f, 0)
                .setColor(255, 255, 255, 255)
                .setUv(0, 0)
                .setOverlay(overlay)
                .setUv2(FULL_BRIGHTNESS & 0xFFFF, FULL_BRIGHTNESS >> 16)
                .setNormal(0, 0, 1);

        // Bottom-left vertex
        consumer.addVertex(pose.pose(), 0.5f, -0.5f, 0)
                .setColor(255, 255, 255, 255)
                .setUv(0, 1)
                .setOverlay(overlay)
                .setUv2(FULL_BRIGHTNESS & 0xFFFF, FULL_BRIGHTNESS >> 16)
                .setNormal(0, 0, 1);

        // Bottom-right vertex
        consumer.addVertex(pose.pose(), -0.5f, -0.5f, 0)
                .setColor(255, 255, 255, 255)
                .setUv(1, 1)
                .setOverlay(overlay)
                .setUv2(FULL_BRIGHTNESS & 0xFFFF, FULL_BRIGHTNESS >> 16)
                .setNormal(0, 0, 1);
    }
}
