package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.ScreenTileLayout;
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
    private static final Map<BlockPos, Long> LAST_DRAW_LOG_NANOS = new ConcurrentHashMap<>();

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(@NotNull ScreenBlockEntity blockEntity, float partialTicks, @NotNull PoseStack poseStack,
                       @NotNull MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        ScreenBlockEntity anchor = blockEntity.isAnchor() ? blockEntity : blockEntity.getAnchorEntity();
        ScreenBlockEntity renderData = anchor != null ? anchor : blockEntity;
        if (renderData.getResolvedImageId() == null || blockEntity.getAnchorPos() == null) return;

        UUID imageId = renderData.getResolvedImageId();
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

        BlockState blockState = blockEntity.getBlockState();
        Direction facing = blockState.hasProperty(ScreenBlock.FACING) ?
            blockState.getValue(ScreenBlock.FACING) : Direction.NORTH;

        DynamicTexture imageTexture = ClientImageManager.getImageTexture(imageId);
        if (imageTexture == null) return;
        float[] scales = calculateScalingFactors(imageTexture, renderData.getScreenWidth(), renderData.getScreenHeight(),
                renderData.isMaintainAspectRatio());
        ScreenTileLayout.Tile tile = calculateTile(blockEntity, renderData, facing, scales[0], scales[1]);
        if (tile.isEmpty()) return;

        debugDraw(blockEntity, texture, facing);

        prepareRenderingTransform(poseStack, renderData, facing);
        renderTextureQuad(texture, tile, poseStack, bufferSource, packedOverlay);
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

    private void renderTextureQuad(ResourceLocation texture, ScreenTileLayout.Tile tile, PoseStack poseStack,
                                   MultiBufferSource bufferSource, int packedOverlay) {
        VertexConsumer vertexBuffer = bufferSource.getBuffer(RenderType.text(texture));
        PoseStack.Pose pose = poseStack.last();

        buildTexturedQuad(vertexBuffer, pose, packedOverlay, tile);
        poseStack.popPose();
    }

    private void buildTexturedQuad(VertexConsumer consumer, PoseStack.Pose pose, int overlay, ScreenTileLayout.Tile tile) {
        // The UV coordinates are intentionally flipped horizontally (U is swapped)
        // to ensure the image displays correctly on the screen. This is not a bug.
        // Top-right vertex
        consumer.addVertex(pose.pose(), tile.minX(), tile.maxY(), 0)
                .setColor(255, 255, 255, 255)
                .setUv(tile.maxU(), tile.minV())
                .setOverlay(overlay)
                .setUv2(FULL_BRIGHTNESS & 0xFFFF, FULL_BRIGHTNESS >> 16)
                .setNormal(0, 0, 1);

        // Top-left vertex
        consumer.addVertex(pose.pose(), tile.maxX(), tile.maxY(), 0)
                .setColor(255, 255, 255, 255)
                .setUv(tile.minU(), tile.minV())
                .setOverlay(overlay)
                .setUv2(FULL_BRIGHTNESS & 0xFFFF, FULL_BRIGHTNESS >> 16)
                .setNormal(0, 0, 1);

        // Bottom-left vertex
        consumer.addVertex(pose.pose(), tile.maxX(), tile.minY(), 0)
                .setColor(255, 255, 255, 255)
                .setUv(tile.minU(), tile.maxV())
                .setOverlay(overlay)
                .setUv2(FULL_BRIGHTNESS & 0xFFFF, FULL_BRIGHTNESS >> 16)
                .setNormal(0, 0, 1);

        // Bottom-right vertex
        consumer.addVertex(pose.pose(), tile.minX(), tile.minY(), 0)
                .setColor(255, 255, 255, 255)
                .setUv(tile.maxU(), tile.maxV())
                .setOverlay(overlay)
                .setUv2(FULL_BRIGHTNESS & 0xFFFF, FULL_BRIGHTNESS >> 16)
                .setNormal(0, 0, 1);
    }
}
