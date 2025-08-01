package com.nstut.simplyscreens.helpers;

import com.mojang.blaze3d.platform.NativeImage;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.RequestImageDownloadC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import com.nstut.simplyscreens.client.gui.widgets.ImageListWidget;
import com.nstut.simplyscreens.client.screens.ImageLoadScreen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientImageManager {
    private static final Path CACHE_DIR = Minecraft.getInstance().gameDirectory.toPath().resolve("simply_screens_cache");
    private static final Map<UUID, DynamicTexture> IN_MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, ImageMetadata> METADATA_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, byte[][]> CHUNK_MAP = new ConcurrentHashMap<>();
    private static final Map<UUID, String> EXTENSION_MAP = new ConcurrentHashMap<>();

    public static void handleImageChunk(UUID imageId, int chunkIndex, int totalChunks, byte[] data, String extension) {
        CHUNK_MAP.computeIfAbsent(imageId, k -> new byte[totalChunks][])[chunkIndex] = data;
        if (extension != null) {
            EXTENSION_MAP.put(imageId, extension);
        }

        boolean allChunksReceived = true;
        for (int i = 0; i < totalChunks; i++) {
            if (CHUNK_MAP.get(imageId)[i] == null) {
                allChunksReceived = false;
                break;
            }
        }

        if (allChunksReceived) {
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                for (int i = 0; i < totalChunks; i++) {
                    outputStream.write(CHUNK_MAP.get(imageId)[i]);
                }
                byte[] imageData = outputStream.toByteArray();
                String fileExtension = EXTENSION_MAP.get(imageId);

                saveImageToCache(imageId, fileExtension, imageData);

                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof ImageLoadScreen imageLoadScreen) {
                    ImageListWidget imageListWidget = imageLoadScreen.getImageListWidget();
                    if (imageListWidget != null) {
                        imageListWidget.receiveImageData(imageId.toString(), imageData);
                    }
                }

            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to reassemble image from chunks", e);
            } finally {
                CHUNK_MAP.remove(imageId);
                EXTENSION_MAP.remove(imageId);
            }
        }
    }

    public static void updateImageCache(List<ImageMetadata> images) {
        METADATA_CACHE.clear();
        for (ImageMetadata image : images) {
            METADATA_CACHE.put(UUID.fromString(image.getId()), image);
        }
    }

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

        ImageMetadata metadata = METADATA_CACHE.get(imageId);
        if (metadata != null) {
            Path imagePath = getImagePath(imageId, metadata.getExtension());
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
        }

        PacketRegistries.CHANNEL.sendToServer(new RequestImageDownloadC2SPacket(imageId));
        return null;
    }

    public static void saveImageToCache(UUID imageId, String extension, byte[] imageData) {
        Path imagePath = getImagePath(imageId, extension);
        try {
            Files.write(imagePath, imageData);

            try (InputStream inputStream = Files.newInputStream(imagePath)) {
                NativeImage nativeImage = NativeImage.read(inputStream);
                DynamicTexture texture = new DynamicTexture(nativeImage);
                IN_MEMORY_CACHE.put(imageId, texture);
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to load image from disk cache after saving", e);
                Files.deleteIfExists(imagePath);
            }
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save image to disk cache", e);
        }
    }

    public static ResourceLocation getTextureLocation(UUID imageId) {
        DynamicTexture texture = getImageTexture(imageId);
        if (texture != null) {
            return Minecraft.getInstance().getTextureManager().register(imageId.toString(), texture);
        }
        return null;
    }

    private static Path getImagePath(UUID imageId, String extension) {
        return CACHE_DIR.resolve(imageId + "." + extension);
    }

    public static void clearCache() {
        IN_MEMORY_CACHE.values().forEach(DynamicTexture::close);
        IN_MEMORY_CACHE.clear();
    }
}