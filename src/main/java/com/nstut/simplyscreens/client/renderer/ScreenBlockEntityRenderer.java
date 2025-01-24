package com.nstut.simplyscreens.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;

public class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {

    private static final Logger LOGGER = Logger.getLogger(ScreenBlockEntityRenderer.class.getName());
    private static final Map<String, ResourceLocation> loadedTextures = new HashMap<>();

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ScreenBlockEntity blockEntity, float partialTicks, @NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // Get the block state and determine the facing direction
        BlockState blockState = blockEntity.getBlockState();
        Direction direction = blockState.getValue(BlockStateProperties.HORIZONTAL_FACING);

        // Get the imagePath from the block entity's NBT tag
        String imagePath = blockEntity.getImagePath();

        // Load the texture if it's not already loaded
        ResourceLocation texture = loadedTextures.get(imagePath);
        if (texture == null) {
            try {
                texture = loadExternalTexture(imagePath);
                loadedTextures.put(imagePath, texture);
            } catch (Exception e) {
                // If loading fails, skip rendering
                return;
            }
        }

        // Push the PoseStack for transformations
        poseStack.pushPose();

        // Translate to the center of the block
        poseStack.translate(0.5, 0.5, 0.5);

        // Rotate based on the facing direction
        float rotationAngle = switch (direction) {
            case NORTH -> 0;
            case SOUTH -> 180;
            case WEST -> 90;
            case EAST -> -90;
            default -> 0;
        };
        poseStack.mulPose(Axis.YP.rotationDegrees(rotationAngle));

        // Translate to the front face of the block
        poseStack.translate(0, 0, -0.501);

        // Scale the image to fit the block face
        poseStack.scale(1.0f, 1.0f, 1.0f);

        // Render the image on the front face of the block
        renderScreenImage(texture, poseStack, bufferSource, packedLight, packedOverlay);

        // Pop the PoseStack to restore the previous state
        poseStack.popPose();
    }

    private ResourceLocation loadExternalTexture(String imagePath) throws IOException {
        // Load the image using the external path
        File imageFile = new File(imagePath);
        BufferedImage bufferedImage = ImageIO.read(imageFile);

        // Convert BufferedImage to NativeImage
        NativeImage nativeImage = new NativeImage(bufferedImage.getWidth(), bufferedImage.getHeight(), false);
        for (int y = 0; y < bufferedImage.getHeight(); y++) {
            for (int x = 0; x < bufferedImage.getWidth(); x++) {
                // Get the ARGB color from the BufferedImage
                int argb = bufferedImage.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blue = argb & 0xFF;

                // Convert ARGB to ABGR
                int abgr = (alpha << 24) | (blue << 16) | (green << 8) | red;

                // Set the color in NativeImage
                nativeImage.setPixelRGBA(x, y, abgr);
            }
        }

        // Create a DynamicTexture with the NativeImage
        DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();

        // Create a unique ResourceLocation
        ResourceLocation textureLocation = new ResourceLocation(SimplyScreens.MOD_ID, "screen_image_" + imageFile.hashCode());
        textureManager.register(textureLocation, dynamicTexture);  // Register the texture

        return textureLocation;
    }

    private void renderScreenImage(ResourceLocation texture, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.entityCutout(texture));
        PoseStack.Pose pose = poseStack.last();

        int fullBrightLight = 15728880;  // Use maximum light level to make the image unaffected by darkness

        // Draw a quad with the texture on the front face
        vertexConsumer.vertex(pose.pose(), -0.5f, 0.5f, 0)
                .color(255, 255, 255, 255)
                .uv(1, 0)
                .overlayCoords(packedOverlay)
                .uv2(fullBrightLight)
                .normal(pose.normal(), 0, 0, 1)
                .endVertex();
        vertexConsumer.vertex(pose.pose(), 0.5f, 0.5f, 0)
                .color(255, 255, 255, 255)
                .uv(0, 0)
                .overlayCoords(packedOverlay)
                .uv2(fullBrightLight)
                .normal(pose.normal(), 0, 0, 1)
                .endVertex();
        vertexConsumer.vertex(pose.pose(), 0.5f, -0.5f, 0)
                .color(255, 255, 255, 255)
                .uv(0, 1)
                .overlayCoords(packedOverlay)
                .uv2(fullBrightLight)
                .normal(pose.normal(), 0, 0, 1)
                .endVertex();
        vertexConsumer.vertex(pose.pose(), -0.5f, -0.5f, 0)
                .color(255, 255, 255, 255)
                .uv(1, 1)
                .overlayCoords(packedOverlay)
                .uv2(fullBrightLight)
                .normal(pose.normal(), 0, 0, 1)
                .endVertex();
    }

    @Override
    public boolean shouldRenderOffScreen(@NotNull ScreenBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 512;
    }
}
