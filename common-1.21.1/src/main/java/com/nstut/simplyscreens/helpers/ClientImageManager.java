package com.nstut.simplyscreens.helpers;

import com.mojang.blaze3d.platform.NativeImage;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.RequestImageDownloadC2SPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;


import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientImageManager {
    private static final Path CACHE_DIR = Minecraft.getInstance().gameDirectory.toPath().resolve("simply_screens_cache");
    private static final Map<UUID, DynamicTexture> IN_MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, ResourceLocation> TEXTURE_LOCATIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, ImageMetadata> METADATA_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, byte[][]> CHUNK_MAP = new ConcurrentHashMap<>();
    private static final Map<UUID, String> EXTENSION_MAP = new ConcurrentHashMap<>();
    private static final Set<UUID> FAILED_IMAGES = ConcurrentHashMap.newKeySet();
    private static final TransferRequestCoordinator<UUID> PENDING_DOWNLOADS =
            new TransferRequestCoordinator<>(Duration.ofSeconds(30));
    private static DynamicTexture errorTexture;
    private static ResourceLocation errorTextureLocation;

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

            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to reassemble image from chunks", e);
            } finally {
                CHUNK_MAP.remove(imageId);
                EXTENSION_MAP.remove(imageId);
                PENDING_DOWNLOADS.release(imageId);
            }
        }
    }

    public static void invalidateImage(UUID imageId) {
        if (imageId == null) return;
        DynamicTexture texture = IN_MEMORY_CACHE.remove(imageId);
        ResourceLocation location = TEXTURE_LOCATIONS.remove(imageId);
        if (location != null) {
            Minecraft.getInstance().getTextureManager().release(location);
        } else if (texture != null) {
            texture.close();
        }
        METADATA_CACHE.remove(imageId);
        CHUNK_MAP.remove(imageId);
        EXTENSION_MAP.remove(imageId);
        FAILED_IMAGES.remove(imageId);
        PENDING_DOWNLOADS.release(imageId);
        try {
            for (String ext : List.of("png", "jpg", "jpeg")) {
                Files.deleteIfExists(getImagePath(imageId, ext));
            }
        } catch (IOException ignored) {}
    }

    public static void updateImageCache(List<ImageMetadata> images) {
        Set<UUID> freshIds = new java.util.HashSet<>();
        if (images != null) {
            for (ImageMetadata image : images) {
                try {
                    freshIds.add(UUID.fromString(image.getId()));
                } catch (Exception ignored) {}
            }
        }
        for (UUID cachedId : Set.copyOf(METADATA_CACHE.keySet())) {
            if (!freshIds.contains(cachedId)) {
                invalidateImage(cachedId);
            }
        }
        METADATA_CACHE.clear();
        if (images != null) {
            for (ImageMetadata image : images) {
                try {
                    METADATA_CACHE.put(UUID.fromString(image.getId()), image);
                } catch (Exception ignored) {}
            }
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
        if (FAILED_IMAGES.contains(imageId)) {
            return getOrCreateErrorTexture();
        }

        if (IN_MEMORY_CACHE.containsKey(imageId)) {
            return IN_MEMORY_CACHE.get(imageId);
        }

        ImageMetadata metadata = METADATA_CACHE.get(imageId);
        if (metadata != null) {
            Path imagePath = getImagePath(imageId, metadata.getExtension());
            if (Files.exists(imagePath)) {
                try (InputStream inputStream = Files.newInputStream(imagePath)) {
                    NativeImage nativeImage = loadImage(inputStream, metadata.getExtension());
                    DynamicTexture texture = new DynamicTexture(nativeImage);
                    IN_MEMORY_CACHE.put(imageId, texture);
                    return texture;
                } catch (IOException e) {
                    SimplyScreens.LOGGER.error("Failed to load image from disk cache", e);
                    FAILED_IMAGES.add(imageId);
                }
            }
        }

        PENDING_DOWNLOADS.tryStart(imageId,
                () -> PacketRegistries.sendToServer(new RequestImageDownloadC2SPacket(imageId)));
        return null;
    }

    public static void saveImageToCache(UUID imageId, String extension, byte[] imageData) {
        Path imagePath = getImagePath(imageId, extension);
        try {
            Files.write(imagePath, imageData);

            try (InputStream inputStream = Files.newInputStream(imagePath)) {
                NativeImage nativeImage = loadImage(inputStream, extension);
                DynamicTexture texture = new DynamicTexture(nativeImage);
                IN_MEMORY_CACHE.put(imageId, texture);
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to load image from disk cache after saving", e);
                FAILED_IMAGES.add(imageId);
                Files.deleteIfExists(imagePath);
            }
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save image to disk cache", e);
        }
    }

    public static ResourceLocation getTextureLocation(UUID imageId) {
        ResourceLocation existing = TEXTURE_LOCATIONS.get(imageId);
        if (existing != null) return existing;

        DynamicTexture texture = getImageTexture(imageId);
        if (texture != null) {
            ResourceLocation location = Minecraft.getInstance().getTextureManager().register(imageId.toString(), texture);
            TEXTURE_LOCATIONS.put(imageId, location);
            return location;
        }
        return null;
    }

    public static void renderThumbnail(GuiGraphics graphics, UUID imageId, int size) {
        ResourceLocation location = getTextureLocation(imageId);
        DynamicTexture texture = IN_MEMORY_CACHE.get(imageId);
        NativeImage pixels = texture != null ? texture.getPixels() : null;
        if (location == null || pixels == null) return;
        int imageWidth = pixels.getWidth();
        int imageHeight = pixels.getHeight();
        if (imageWidth <= 0 || imageHeight <= 0 || size <= 0) return;

        float scale = Math.min((float) size / imageWidth, (float) size / imageHeight);
        int drawWidth = Math.max(1, Math.round(imageWidth * scale));
        int drawHeight = Math.max(1, Math.round(imageHeight * scale));
        int offsetX = (size - drawWidth) / 2;
        int offsetY = (size - drawHeight) / 2;

        graphics.pose().pushPose();
        graphics.pose().translate(offsetX, offsetY, 0);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.blit(location, 0, 0, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
        graphics.pose().popPose();
    }

    private static NativeImage loadImage(InputStream inputStream, String extension) throws IOException {
        if ("png".equals(extension)) {
            return NativeImage.read(inputStream);
        }
        BufferedImage bi = ImageIO.read(inputStream);
        if (bi == null) {
            throw new IOException("Failed to decode image");
        }
        int width = bi.getWidth();
        int height = bi.getHeight();
        NativeImage ni = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = bi.getRGB(x, y);
                int a = rgb >> 24 & 0xFF;
                int r = rgb >> 16 & 0xFF;
                int g = rgb >> 8 & 0xFF;
                int b = rgb & 0xFF;
                int color = (a << 24) | (b << 16) | (g << 8) | r;
                ni.setPixelRGBA(x, y, color);
            }
        }
        return ni;
    }

    private static DynamicTexture getOrCreateErrorTexture() {
        if (errorTexture != null) {
            return errorTexture;
        }

        BufferedImage bi = new BufferedImage(256, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(32, 32, 32, 200));
        g.fillRect(0, 0, 256, 64);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        FontMetrics fm = g.getFontMetrics();
        String msg = "Something is wrong with the image";
        int x = (256 - fm.stringWidth(msg)) / 2;
        int y = (64 - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(msg, x, y);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(bi, "png", baos);
            NativeImage image = NativeImage.read(new ByteArrayInputStream(baos.toByteArray()));
            errorTexture = new DynamicTexture(image);
            UUID errorId = UUID.nameUUIDFromBytes("error".getBytes());
            errorTextureLocation = Minecraft.getInstance().getTextureManager().register(errorId.toString(), errorTexture);
            return errorTexture;
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to create error texture", e);
            return null;
        }
    }

    private static Path getImagePath(UUID imageId, String extension) {
        return CACHE_DIR.resolve(imageId + "." + extension);
    }

    public static void clearCache() {
        for (Map.Entry<UUID, ResourceLocation> entry : TEXTURE_LOCATIONS.entrySet()) {
            Minecraft.getInstance().getTextureManager().release(entry.getValue());
            IN_MEMORY_CACHE.remove(entry.getKey());
        }
        TEXTURE_LOCATIONS.clear();
        IN_MEMORY_CACHE.values().forEach(DynamicTexture::close);
        IN_MEMORY_CACHE.clear();
    }
}
