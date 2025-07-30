package com.nstut.simplyscreens.helpers;

import com.mojang.blaze3d.platform.NativeImage;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.RequestImageDownloadC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientImageManager {
    private static final Path CACHE_DIR = Minecraft.getInstance().gameDirectory.toPath().resolve("simply_screens_cache");
    private static final Map<UUID, DynamicTexture> IN_MEMORY_CACHE = new ConcurrentHashMap<>();

    public static void initialize() {
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to create cache directory", e);
        }
    }

    public static DynamicTexture getImageTexture(UUID imageId) {
        if (IN_MEMORY_CACHE.containsKey(imageId)) {
            return IN_MEMORY_CACHE.get(imageId);
        }

        Path imagePath = getImagePath(imageId);
        if (Files.exists(imagePath)) {
            try (InputStream inputStream = Files.newInputStream(imagePath)) {
                NativeImage nativeImage = NativeImage.read(inputStream);
                DynamicTexture texture = new DynamicTexture(nativeImage);
                IN_MEMORY_CACHE.put(imageId, texture);
                return texture;
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to load image from disk cache", e);
            }
        }

        PacketRegistries.CHANNEL.sendToServer(new RequestImageDownloadC2SPacket(imageId));
        return null;
    }

    public static void saveImageToCache(UUID imageId, byte[] imageData) {
        Path imagePath = getImagePath(imageId);
        try {
            Files.write(imagePath, imageData);
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save image to disk cache", e);
        }

        try (InputStream inputStream = Files.newInputStream(imagePath)) {
            NativeImage nativeImage = NativeImage.read(inputStream);
            DynamicTexture texture = new DynamicTexture(nativeImage);
            IN_MEMORY_CACHE.put(imageId, texture);
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to load image from disk cache after saving", e);
        }
    }

    public static ResourceLocation getTextureLocation(UUID imageId) {
        DynamicTexture texture = getImageTexture(imageId);
        if (texture != null) {
            return Minecraft.getInstance().getTextureManager().register(imageId.toString(), texture);
        }
        return null;
    }

    private static Path getImagePath(UUID imageId) {
        return CACHE_DIR.resolve(imageId.toString() + ".png");
    }

    public static void clearCache() {
        IN_MEMORY_CACHE.values().forEach(DynamicTexture::close);
        IN_MEMORY_CACHE.clear();
    }
}