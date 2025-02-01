package com.nstut.simplyscreens.client.renderers;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;

public class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
    private static final Logger LOGGER = Logger.getLogger(ScreenBlockEntityRenderer.class.getName());
    private static final Map<String, ResourceLocation> TEXTURE_CACHE = new HashMap<>();
    private static final int FULL_BRIGHTNESS = 15728880;
    private static final float BASE_OFFSET = 0.501f;

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(@NotNull ScreenBlockEntity blockEntity, float partialTicks, @NotNull PoseStack poseStack,
                       @NotNull MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!shouldRender(blockEntity)) return;

        BlockState blockState = blockEntity.getBlockState();
        Direction facing = blockState.getValue(BlockStateProperties.FACING);
        String imagePath = blockEntity.getImagePath();

        ResourceLocation texture = getOrLoadTexture(imagePath);
        if (texture == null) return;

        prepareRenderingTransform(poseStack, blockEntity, facing);
        renderTextureQuad(texture, poseStack, bufferSource, packedOverlay);
    }

    @Override
    public int getViewDistance() {
        return Config.VIEW_DISTANCE.get();
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
        switch (facing) {
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
            // NORTH: no rotation needed
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

    private boolean shouldRender(ScreenBlockEntity blockEntity) {
        return blockEntity.isAnchor() &&
                !blockEntity.getImagePath().isEmpty();
    }

    private ResourceLocation getOrLoadTexture(String imagePath) {
        return TEXTURE_CACHE.computeIfAbsent(imagePath, path -> {
            try {
                return loadTextureResource(path);
            } catch (Exception e) {
                LOGGER.warning("Failed to load texture: " + path + " - " + e.getMessage());
                return null;
            }
        });
    }

    private ResourceLocation loadTextureResource(String path) throws IOException {
        if (isRemoteResource(path)) {
            return loadWebTexture(new URL(path));
        }
        return loadLocalTexture(new File(path));
    }

    private boolean isRemoteResource(String path) {
        return path.startsWith("http://") || path.startsWith("https://");
    }

    private void applyAspectRatioScaling(PoseStack poseStack, ScreenBlockEntity blockEntity) {
        // Get fresh values directly from block entity
        int width = blockEntity.getScreenWidth();
        int height = blockEntity.getScreenHeight();
        boolean maintainAspect = blockEntity.isMaintainAspectRatio();

        ResourceLocation texture = TEXTURE_CACHE.get(blockEntity.getImagePath());
        if (texture == null) return;

        float[] scales = calculateScalingFactors(texture, width, height, maintainAspect);
        poseStack.scale(scales[0], scales[1], 1.0f);
    }

    private float[] calculateScalingFactors(ResourceLocation texture, int width, int height, boolean keepAspect) {
        if (!keepAspect) {
            return new float[]{width, height};
        }

        NativeImage image = getTextureImage(texture);
        if (image == null) return new float[]{1, 1};

        float imageAspect = (float) image.getWidth() / image.getHeight();
        float screenAspect = (float) width / height;

        return imageAspect > screenAspect ?
                new float[]{width, width / imageAspect} :
                new float[]{height * imageAspect, height};
    }

    private NativeImage getTextureImage(ResourceLocation texture) {
        DynamicTexture dynamicTexture = (DynamicTexture) Minecraft.getInstance()
                .getTextureManager()
                .getTexture(texture);

        return dynamicTexture.getPixels();
    }

    private void renderTextureQuad(ResourceLocation texture, PoseStack poseStack,
                                   MultiBufferSource bufferSource, int packedOverlay) {
        VertexConsumer vertexBuffer = bufferSource.getBuffer(RenderType.text(texture));
        PoseStack.Pose pose = poseStack.last();

        buildTexturedQuad(vertexBuffer, pose, packedOverlay);
        poseStack.popPose();
    }

    private void buildTexturedQuad(VertexConsumer consumer, PoseStack.Pose pose, int overlay) {
        // Top-right vertex
        consumer.vertex(pose.pose(), -0.5f, 0.5f, 0)
                .color(255, 255, 255, 255)
                .uv(1, 0)
                .overlayCoords(overlay)
                .uv2(FULL_BRIGHTNESS)
                .normal(pose.normal(), 0, 0, 1)
                .endVertex();

        // Top-left vertex
        consumer.vertex(pose.pose(), 0.5f, 0.5f, 0)
                .color(255, 255, 255, 255)
                .uv(0, 0)
                .overlayCoords(overlay)
                .uv2(FULL_BRIGHTNESS)
                .normal(pose.normal(), 0, 0, 1)
                .endVertex();

        // Bottom-left vertex
        consumer.vertex(pose.pose(), 0.5f, -0.5f, 0)
                .color(255, 255, 255, 255)
                .uv(0, 1)
                .overlayCoords(overlay)
                .uv2(FULL_BRIGHTNESS)
                .normal(pose.normal(), 0, 0, 1)
                .endVertex();

        // Bottom-right vertex
        consumer.vertex(pose.pose(), -0.5f, -0.5f, 0)
                .color(255, 255, 255, 255)
                .uv(1, 1)
                .overlayCoords(overlay)
                .uv2(FULL_BRIGHTNESS)
                .normal(pose.normal(), 0, 0, 1)
                .endVertex();
    }

    // Shared texture loading implementation
    private ResourceLocation loadWebTexture(URL url) throws IOException {
        try (InputStream stream = url.openStream()) {
            return createTextureResource(ImageIO.read(stream), url.toString());
        }
    }

    private ResourceLocation loadLocalTexture(File file) throws IOException {
        return createTextureResource(ImageIO.read(file), file.getName());
    }

    private ResourceLocation createTextureResource(BufferedImage image, String sourceId) {
        try (NativeImage nativeImage = convertToNativeImage(image)) {
            DynamicTexture texture = new DynamicTexture(nativeImage);
            ResourceLocation location = new ResourceLocation(
                    SimplyScreens.MOD_ID,
                    "screen_tex/" + sourceId.hashCode()
            );

            Minecraft.getInstance().getTextureManager().register(location, texture);
            return location;
        }
    }

    private NativeImage convertToNativeImage(BufferedImage image) {
        NativeImage nativeImage = new NativeImage(image.getWidth(), image.getHeight(), false);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                nativeImage.setPixelRGBA(x, y, convertARGBtoABGR(argb));
            }
        }
        return nativeImage;
    }

    private int convertARGBtoABGR(int argb) {
        return (argb & 0xFF000000) |
                ((argb & 0x00FF0000) >> 16) |
                (argb & 0x0000FF00) |
                ((argb & 0x000000FF) << 16);
    }
}