package com.nstut.simplyscreens.client.helpers;

import com.google.common.hash.Hashing;
import com.mojang.blaze3d.platform.NativeImage;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ImageUtils {

    public static ResourceLocation createTextureResource(BufferedImage image, String imageId) {
        if (image == null) {
            SimplyScreens.LOGGER.error("Cannot create texture from null image for id: {}", imageId);
            return null;
        }
        try {
            NativeImage nativeImage = convertBufferedImageToNativeImage(image);
            if (nativeImage == null) {
                SimplyScreens.LOGGER.error("Failed to convert BufferedImage to NativeImage for id: {}", imageId);
                return null;
            }

            String textureId = "screen_tex/" + imageId;
            ResourceLocation resourceLocation = new ResourceLocation(SimplyScreens.MOD_ID, textureId);

            Minecraft.getInstance().getTextureManager().register(resourceLocation, new DynamicTexture(nativeImage));
            SimplyScreens.LOGGER.info("Registered new texture resource for {} at {}", imageId, resourceLocation);
            return resourceLocation;
        } catch (Exception e) {
            SimplyScreens.LOGGER.error("Failed to create texture resource for " + imageId, e);
            return null;
        }
    }

    private static NativeImage convertBufferedImageToNativeImage(BufferedImage bufferedImage) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "png", outputStream);
            try (InputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray())) {
                return NativeImage.read(inputStream);
            }
        }
    }

}