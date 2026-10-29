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
    // Slideshow browsing must not retain one GPU allocation per slide indefinitely.
    private static final long MAX_TEXTURE_BYTES = 128L * 1024 * 1024;
    private static final int MAX_TEXTURES = 32;
    private static final java.util.LinkedHashMap<UUID, Long> TEXTURE_BUDGET = new java.util.LinkedHashMap<>(32, .75f, true);
    private static final Map<UUID, String> CACHED_EXTENSIONS = new ConcurrentHashMap<>();
    private static long prefetchWindow;
    private static int prefetchRequests;
    public static void prefetch(UUID image) {
        if (image == null || IN_MEMORY_CACHE.containsKey(image) || FAILED_IMAGES.contains(image)) return;
        long now = System.nanoTime();
        if (now - prefetchWindow >= 2_000_000_000L) { prefetchWindow = now; prefetchRequests = 0; }
        if (prefetchRequests >= 2) return;
        prefetchRequests++;
        getImageTexture(image);
    }
    private static void cacheTexture(UUID id, DynamicTexture texture) {
        var pixels = texture.getPixels();
        long bytes = pixels == null ? 0 : (long)pixels.getWidth()*pixels.getHeight()*4;
        evictTexture(id);
        while (!TEXTURE_BUDGET.isEmpty() && (TEXTURE_BUDGET.size() >= MAX_TEXTURES
                || TEXTURE_BUDGET.values().stream().mapToLong(Long::longValue).sum()+bytes > MAX_TEXTURE_BYTES))
            evictTexture(TEXTURE_BUDGET.keySet().iterator().next());
        IN_MEMORY_CACHE.put(id,texture); TEXTURE_BUDGET.put(id,bytes);
    }
    private static void evictTexture(UUID id) {
        TEXTURE_BUDGET.remove(id);
        var location = TEXTURE_LOCATIONS.remove(id);
        var texture = IN_MEMORY_CACHE.remove(id);
        if (location != null) Minecraft.getInstance().getTextureManager().release(location);
        else if (texture != null) texture.close();
    }

    private static final Path CACHE_DIR = Minecraft.getInstance().gameDirectory.toPath().resolve("simply_screens_cache");
    private static final Map<UUID, DynamicTexture> IN_MEMORY_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, ResourceLocation> TEXTURE_LOCATIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, ImageMetadata> METADATA_CACHE = new ConcurrentHashMap<>();
    private static final MediaDownloadReceiver DOWNLOADS = new MediaDownloadReceiver();
    private static final MediaDownloadRequests REQUEST_IDS = new MediaDownloadRequests();
    private static final Map<UUID, Object> CACHE_LOADS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService CACHE_IO = MediaExecutors.newDaemonBoundedThreadPool(2, 32, "Simply Screens Cache IO");
    // Shared with invalidation, peer resets and cache clear; disk staging never holds this lock.
    private static final Object CACHE_PUBLICATION_LOCK = new Object();
    private static volatile long cacheEpoch;
    private static Object transferPeer;
    private static final Set<UUID> FAILED_IMAGES = ConcurrentHashMap.newKeySet();
    private static final TransferRequestCoordinator<UUID> PENDING_DOWNLOADS =
            new TransferRequestCoordinator<>(Duration.ofSeconds(30));
    // A failed request must release the in-flight timer, but should not retry every render frame.
    private static final ExpiringRequestSet<UUID> RETRY_BACKOFF =
            new ExpiringRequestSet<>(Duration.ofSeconds(2));
    private static DynamicTexture errorTexture;
    private static ResourceLocation errorTextureLocation;

    public static void handleImageChunk(UUID imageId, UUID transferId, int chunkIndex, int totalChunks, byte[] data, String extension) {
        if (!REQUEST_IDS.touch(imageId, transferId)) return;
        Object peer = Minecraft.getInstance().getConnection();
        long epoch = cacheEpoch;
        if (chunkIndex == -1 && data.length == 0) { failDownload(imageId, transferId); return; }
        try {
            CACHE_IO.execute(() -> {
                if (epoch != cacheEpoch || !transferId.equals(REQUEST_IDS.get(imageId))) return;
                MediaDownloadReceiver.Download download = DOWNLOADS.accept(imageId, transferId, chunkIndex, totalChunks, data, extension);
                boolean accepted = transferId.equals(REQUEST_IDS.get(imageId)) && (download != null
                        ? saveImageToCache(imageId, download.extension(), download.bytes(), epoch, transferId, peer)
                        : DOWNLOADS.isPending(imageId));
                long receipt = accepted ? (long)chunkIndex * com.nstut.packetweave.api.OutboundTransfers.CHUNK_BYTES + data.length : -1;
                Minecraft.getInstance().execute(() -> {
                    if (epoch != cacheEpoch || peer != Minecraft.getInstance().getConnection()
                            || !transferId.equals(REQUEST_IDS.get(imageId))) return;
                    PacketRegistries.sendToServer(new com.nstut.simplyscreens.network.ImageTransferReceiptC2SPacket(transferId, receipt));
                    if (!accepted || download != null) {
                        if (!accepted) failDownload(imageId, transferId);
                        else if (REQUEST_IDS.remove(imageId, transferId)) {
                            PENDING_DOWNLOADS.release(imageId);
                            RETRY_BACKOFF.release(imageId);
                        }
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            failDownload(imageId, transferId);
            PacketRegistries.sendToServer(new com.nstut.simplyscreens.network.ImageTransferReceiptC2SPacket(transferId, -1));
        }
    }

    private static void failDownload(UUID imageId, UUID transferId) {
        // Ignore stale worker completions: they must not cancel a newer retry.
        synchronized (CACHE_PUBLICATION_LOCK) {
            if (!REQUEST_IDS.remove(imageId, transferId)) return;
        }
        DOWNLOADS.cancel(imageId);
        PENDING_DOWNLOADS.release(imageId);
        RETRY_BACKOFF.tryAcquire(imageId);
    }

    public static void invalidateImage(UUID imageId) {
        if (imageId == null) return;
        TEXTURE_BUDGET.remove(imageId); CACHED_EXTENSIONS.remove(imageId); CACHE_LOADS.remove(imageId);
        DynamicTexture texture = IN_MEMORY_CACHE.remove(imageId);
        ResourceLocation location = TEXTURE_LOCATIONS.remove(imageId);
        if (location != null) {
            Minecraft.getInstance().getTextureManager().release(location);
        } else if (texture != null) {
            texture.close();
        }
        METADATA_CACHE.remove(imageId);
        synchronized (CACHE_PUBLICATION_LOCK) {
            REQUEST_IDS.remove(imageId);
            try {
                for (String ext : List.of("png", "jpg", "jpeg")) {
                    Files.deleteIfExists(getImagePath(imageId, ext));
                }
            } catch (IOException error) {
                SimplyScreens.LOGGER.warn("Could not remove invalidated cached image {}", imageId, error);
            }
        }
        DOWNLOADS.cancel(imageId);
        FAILED_IMAGES.remove(imageId);
        PENDING_DOWNLOADS.release(imageId);
        RETRY_BACKOFF.release(imageId);
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
        dev.architectury.event.events.client.ClientTickEvent.CLIENT_POST.register(client -> {
            Object currentPeer = Minecraft.getInstance().getConnection();
            if (currentPeer != transferPeer) {
                if (transferPeer != null) com.nstut.packetweave.api.OutboundTransfers.shared().cancelPeer(transferPeer);
                synchronized (CACHE_PUBLICATION_LOCK) {
                    cacheEpoch++; REQUEST_IDS.clear();
                    transferPeer = currentPeer;
                }
                CACHE_LOADS.clear(); DOWNLOADS.clear(); PENDING_DOWNLOADS.clear(); RETRY_BACKOFF.clear();
            }
            synchronized (CACHE_PUBLICATION_LOCK) {
                for (UUID expired : DOWNLOADS.reapExpired()) { REQUEST_IDS.remove(expired); PENDING_DOWNLOADS.release(expired); }
                for (UUID expired : REQUEST_IDS.reapExpired()) { DOWNLOADS.cancel(expired); PENDING_DOWNLOADS.release(expired); }
            }
        });
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
            TEXTURE_BUDGET.get(imageId);
            return IN_MEMORY_CACHE.get(imageId);
        }

        ImageMetadata metadata = METADATA_CACHE.get(imageId);
        if (metadata == null && CACHED_EXTENSIONS.containsKey(imageId))
            metadata = new ImageMetadata(imageId.toString(), imageId.toString(), CACHED_EXTENSIONS.get(imageId));
        if (metadata != null && MediaFiles.isImageExtension(metadata.getExtension())) {
            if (CACHE_LOADS.containsKey(imageId)) return null;
            Path imagePath = getImagePath(imageId, metadata.getExtension());
            if (Files.exists(imagePath)) {
                queueCachedImage(imageId, imagePath, metadata.getExtension());
                return null;
            }
        }

        if (RETRY_BACKOFF.isPending(imageId)) return null;
        DOWNLOADS.requestIfIdle(imageId, PENDING_DOWNLOADS,
                () -> {
                    UUID request = REQUEST_IDS.begin(imageId);
                    if (request == null) { PENDING_DOWNLOADS.release(imageId); return; }
                    DOWNLOADS.expect(imageId, request);
                    PacketRegistries.sendToServer(new RequestImageDownloadC2SPacket(imageId, request));
                });
        return null;
    }

    private static void queueCachedImage(UUID imageId, Path path, String extension) {
        Object token = new Object();
        if (CACHE_LOADS.putIfAbsent(imageId, token) != null) return;
        long epoch = cacheEpoch;
        try {
            CACHE_IO.execute(() -> {
                try (InputStream input = Files.newInputStream(path)) {
                    NativeImage image = loadImage(input, extension);
                    Minecraft.getInstance().execute(() -> {
                        if (epoch != cacheEpoch || !CACHE_LOADS.remove(imageId, token)) { image.close(); return; }
                        cacheTexture(imageId, new DynamicTexture(image));
                    });
                } catch (IOException | RuntimeException error) {
                    Minecraft.getInstance().execute(() -> {
                        if (epoch == cacheEpoch && CACHE_LOADS.remove(imageId, token)) FAILED_IMAGES.add(imageId);
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { CACHE_LOADS.remove(imageId, token); }
    }

    public static void saveImageToCache(UUID imageId, String extension, byte[] imageData) {
        saveImageToCache(imageId, extension, imageData, cacheEpoch, REQUEST_IDS.get(imageId),
                Minecraft.getInstance().getConnection());
    }

    private static boolean saveImageToCache(UUID imageId, String extension, byte[] imageData,
                                            long epoch, UUID request, Object peer) {
        if (!MediaFiles.isImageExtension(extension) || request == null || epoch != cacheEpoch
                || !request.equals(REQUEST_IDS.get(imageId)) || peer != Minecraft.getInstance().getConnection()) return false;
        Path imagePath = getImagePath(imageId, extension);
        try {
            NativeImage nativeImage = MediaFiles.decodeAndWriteAtomically(imagePath, imageData,
                    bytes -> loadImage(new ByteArrayInputStream(bytes), extension),
                    CACHE_PUBLICATION_LOCK,
                    () -> epoch == cacheEpoch && request.equals(REQUEST_IDS.get(imageId))
                            && peer == Minecraft.getInstance().getConnection());
            if (nativeImage == null) return false;
            Minecraft.getInstance().execute(() -> {
                if (epoch != cacheEpoch || !request.equals(REQUEST_IDS.get(imageId))
                        || peer != Minecraft.getInstance().getConnection()) { nativeImage.close(); return; }
                CACHED_EXTENSIONS.put(imageId, extension);
                cacheTexture(imageId, new DynamicTexture(nativeImage));
            });
            return true;
        } catch (IOException | RuntimeException error) {
            SimplyScreens.LOGGER.error("Failed saving image cache", error);
            return false;
        }
    }

    public static ResourceLocation getTextureLocation(UUID imageId) {
        ResourceLocation existing = TEXTURE_LOCATIONS.get(imageId);
        if (existing != null) { TEXTURE_BUDGET.get(imageId); return existing; }

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
        int limit = com.nstut.simplyscreens.Config.MAX_UPLOAD_SIZE_LIMIT;
        byte[] encoded = MediaFiles.readBounded(inputStream, limit);
        try (var imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {
            var readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new IOException("Unsupported image encoding");
            var reader = readers.next();
            try {
                reader.setInput(imageInput);
                long pixels = (long)reader.getWidth(0)*reader.getHeight(0);
                if (pixels <= 0 || pixels > 16L*1024*1024) throw new IOException("Image exceeds 16 megapixel decode budget");
            } finally { reader.dispose(); }
        }
        inputStream = new ByteArrayInputStream(encoded);
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
        synchronized (CACHE_PUBLICATION_LOCK) { cacheEpoch++; REQUEST_IDS.clear(); }
        CACHE_LOADS.clear();
        TEXTURE_BUDGET.clear(); CACHED_EXTENSIONS.clear(); prefetchWindow = 0; prefetchRequests = 0;
        DOWNLOADS.clear();
        PENDING_DOWNLOADS.clear();
        RETRY_BACKOFF.clear();
        METADATA_CACHE.clear();
        FAILED_IMAGES.clear();
        for (Map.Entry<UUID, ResourceLocation> entry : TEXTURE_LOCATIONS.entrySet()) {
            Minecraft.getInstance().getTextureManager().release(entry.getValue());
            IN_MEMORY_CACHE.remove(entry.getKey());
        }
        TEXTURE_LOCATIONS.clear();
        IN_MEMORY_CACHE.values().forEach(DynamicTexture::close);
        IN_MEMORY_CACHE.clear();
    }
}
