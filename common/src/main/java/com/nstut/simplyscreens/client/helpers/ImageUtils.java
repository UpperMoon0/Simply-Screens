package com.nstut.simplyscreens.client.helpers;

import com.mojang.blaze3d.platform.NativeImage;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.awt.image.BufferedImage;

public class ImageUtils {
    private static final Logger LOGGER = SimplyScreens.LOGGER;

    public static ResourceLocation createTextureResource(BufferedImage image, String sourceId) {
        try (NativeImage nativeImage = convertToNativeImage(image)) {
            DynamicTexture texture = new DynamicTexture(nativeImage);
            ResourceLocation location = new ResourceLocation(
                    SimplyScreens.MOD_ID,
                    "screen_tex/" + sourceId.hashCode()
            );

            Minecraft.getInstance().getTextureManager().register(location, texture);
            LOGGER.info("Registered new texture resource for {} at {}", sourceId, location);
            return location;
        } catch (Exception e) {
            LOGGER.error("Failed to create texture resource for {}", sourceId, e);
            return null;
        }
    }

    public static NativeImage convertToNativeImage(BufferedImage image) {
        NativeImage nativeImage = new NativeImage(image.getWidth(), image.getHeight(), false);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                nativeImage.setPixelRGBA(x, y, convertARGBtoABGR(argb));
            }
        }
        return nativeImage;
    }

    public static int convertARGBtoABGR(int argb) {
        return (argb & 0xFF000000) |
                ((argb & 0x00FF0000) >> 16) |
                (argb & 0x0000FF00) |
                ((argb & 0x000000FF) << 16);
    }
}