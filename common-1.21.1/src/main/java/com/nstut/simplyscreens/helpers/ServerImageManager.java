package com.nstut.simplyscreens.helpers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.UpdateImageListS2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class ServerImageManager {
    private static final long MAX_IMAGE_PIXELS = 16_777_216L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int UPLOAD_CHUNK_SIZE = 32768;
    private static final long UPLOAD_TTL_MILLIS = 60_000L;
    private static final int MAX_ACTIVE_UPLOADS_PER_PLAYER = 4;
    private static final Map<String, UploadState> UPLOADS = new ConcurrentHashMap<>();
    private static final ExecutorService IMAGE_UPLOAD_EXECUTOR =
            ChunkedFileTransfer.newDaemonFixedThreadPool(2, "Simply Screens Image Upload");
    private static final java.util.concurrent.ScheduledExecutorService UPLOAD_REAPER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Simply Screens Upload Reaper");
                thread.setDaemon(true);
                return thread;
            });
    static {
        UPLOAD_REAPER.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            UPLOADS.entrySet().removeIf(entry -> now - entry.getValue().lastActivity > UPLOAD_TTL_MILLIS);
        }, UPLOAD_TTL_MILLIS, UPLOAD_TTL_MILLIS, TimeUnit.MILLISECONDS);
    }
    private static volatile List<ImageMetadata> cachedImageList;
    private static volatile Path cachedImagesDirectory;

    public static void handleImageChunk(ServerPlayer player, BlockPos blockPos, UUID transactionId, int chunkIndex, int totalChunks, byte[] data, String fileName) {
        if (!ScreenPacketSecurity.canModify(player, blockPos) || Config.DISABLE_UPLOAD) return;
        long now = System.currentTimeMillis();
        UPLOADS.entrySet().removeIf(entry -> now - entry.getValue().lastActivity > UPLOAD_TTL_MILLIS);
        int maxChunks = Math.max(1, (Config.MAX_UPLOAD_SIZE + UPLOAD_CHUNK_SIZE - 1) / UPLOAD_CHUNK_SIZE);
        if (data == null || data.length == 0 || data.length > UPLOAD_CHUNK_SIZE || chunkIndex < 0 ||
                totalChunks <= 0 || totalChunks > maxChunks || chunkIndex >= totalChunks) {
            SimplyScreens.LOGGER.warn("Ignoring invalid image upload chunk {} of {} for transaction {}", chunkIndex, totalChunks, transactionId);
            return;
        }

        String uploadKey = player.getUUID() + ":" + transactionId;
        long activeForPlayer = UPLOADS.values().stream().filter(s -> s.owner.equals(player.getUUID())).count();
        UploadState existing = UPLOADS.get(uploadKey);
        if (existing == null && activeForPlayer >= MAX_ACTIVE_UPLOADS_PER_PLAYER) {
            SimplyScreens.LOGGER.warn("Rejecting excess concurrent upload from {}", player.getUUID());
            return;
        }
        UploadState state = UPLOADS.computeIfAbsent(uploadKey, k -> new UploadState(player.getUUID(), totalChunks, now));
        if (state.totalChunks != totalChunks) {
            SimplyScreens.LOGGER.warn("Ignoring image upload transaction {} with mismatched chunk count {} (expected {})", transactionId, totalChunks, state.totalChunks);
            UPLOADS.remove(uploadKey);
            return;
        }

        boolean allChunksReceived;
        synchronized (state) {
            if (state.chunks[chunkIndex] == null && state.receivedBytes + data.length > Config.MAX_UPLOAD_SIZE) {
                SimplyScreens.LOGGER.warn("Rejecting image upload transaction {} because received bytes would exceed configured limit of {}", transactionId, Config.MAX_UPLOAD_SIZE);
                UPLOADS.remove(uploadKey);
                return;
            }

            state.addChunk(chunkIndex, data, fileName);
            state.lastActivity = now;
            allChunksReceived = state.isComplete();
        }

        if (allChunksReceived) {
            UPLOADS.remove(uploadKey);
            MinecraftServer server = player.getServer();
            String ownerUUID = player.getUUID().toString();

            IMAGE_UPLOAD_EXECUTOR.execute(() -> finishImageUpload(server, player, blockPos, state, ownerUUID));
        }
    }

    private static void finishImageUpload(MinecraftServer server, ServerPlayer player, BlockPos blockPos, UploadState state, String ownerUUID) {
        try {
            byte[] imageData = new byte[(int) state.receivedBytes];
            int offset = 0;
            for (byte[] chunk : state.chunks) {
                System.arraycopy(chunk, 0, imageData, offset, chunk.length);
                offset += chunk.length;
            }

            UUID imageId = saveImage(server, state.originalName, imageData, null, ownerUUID);
            if (imageId != null) {
                List<ImageMetadata> images = getImageListForPlayer(server, ownerUUID);
                server.execute(() -> {
                    if (player.level().getBlockEntity(blockPos) instanceof com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity screen) {
                        screen.setImageId(imageId);
                    }

                    PacketRegistries.sendToPlayer(player, new UpdateImageListS2CPacket(images));
                });
            }
        } catch (RuntimeException e) {
            SimplyScreens.LOGGER.error("Failed to finish image upload", e);
        }
    }

    public static UUID saveImage(MinecraftServer server, String originalName, byte[] data, String contentType) {
        return saveImage(server, originalName, data, contentType, null);
    }

    public static synchronized UUID saveImage(MinecraftServer server, String originalName, byte[] data, String contentType, String ownerUUID) {
        try {
            if (data == null || data.length > Math.max(Config.MAX_UPLOAD_SIZE, Config.MAX_URL_DOWNLOAD_SIZE) ||
                    !hasStorageCapacity(server, ownerUUID, data == null ? 0 : data.length)) {
                SimplyScreens.LOGGER.warn("Rejecting image because its owner/server storage quota would be exceeded");
                return null;
            }
            String extension = getImageExtension(data);

            if (extension == null) {
                SimplyScreens.LOGGER.error("Could not determine a valid image type for '{}' based on its content. It might be corrupted or an unsupported format.", originalName);
                return null;
            }

            if (!validateImageDimensions(data, originalName)) {
                return null;
            }

            SimplyScreens.LOGGER.info("Saving image. originalName: '{}', contentType: '{}', determined extension: '{}'", originalName, contentType, extension);

            if (!"png".equals(extension)) {
                try {
                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
                    if (image != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(image, "png", baos);
                        data = baos.toByteArray();
                        extension = "png";
                    }
                } catch (IOException e) {
                    SimplyScreens.LOGGER.error("Failed to convert image to PNG", e);
                    return null;
                }
            }

            UUID imageId = UUID.randomUUID();
            Path imagesDir = getImagesDir(server);

            if (!imagesDir.toFile().exists()) {
                imagesDir.toFile().mkdirs();
            }

            File imageFile = imagesDir.resolve(imageId + "." + extension).toFile();
            try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                fos.write(data);
            }

            File metadataFile = imagesDir.resolve(imageId + ".json").toFile();
            String safeOriginalName = originalName != null && !originalName.isBlank() ? originalName : "uploaded_image";
            String nameWithoutExtension = safeOriginalName.contains(".") ? safeOriginalName.substring(0, safeOriginalName.lastIndexOf('.')) : safeOriginalName;
            ImageMetadata metadata = new ImageMetadata(nameWithoutExtension, imageId.toString(), extension, ownerUUID);
            try (FileWriter writer = new FileWriter(metadataFile)) {
                GSON.toJson(metadata, writer);
            }

            cachedImageList = null;

            return imageId;
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save image", e);
            return null;
        }
    }

    public static boolean deleteImage(MinecraftServer server, UUID imageId, String requesterUUID) {
        ImageMetadata metadata = getImageMetadata(server, imageId);
        if (metadata == null) {
            return false;
        }

        if (metadata.getOwnerUUID() == null || !metadata.getOwnerUUID().equals(requesterUUID)) {
            SimplyScreens.LOGGER.warn("Player {} tried to delete image {} owned by {}", requesterUUID, imageId, metadata.getOwnerUUID());
            return false;
        }

        Path imagesDir = getImagesDir(server);
        try {
            Files.deleteIfExists(imagesDir.resolve(imageId + "." + metadata.getExtension()));
            Files.deleteIfExists(imagesDir.resolve(imageId + ".json"));
            cachedImageList = null;
            return true;
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to delete image {}", imageId, e);
            return false;
        }
    }

    private static String getImageExtension(byte[] data) {
        if (data == null || data.length < 4) {
            SimplyScreens.LOGGER.warn("Image data is null or too small (size: {})", data == null ? 0 : data.length);
            return null;
        }

        // Log first bytes for debugging
        StringBuilder hexBuilder = new StringBuilder("First bytes: ");
        for (int i = 0; i < Math.min(16, data.length); i++) {
            hexBuilder.append(String.format("%02X ", data[i]));
        }
        SimplyScreens.LOGGER.info(hexBuilder.toString());

        // PNG: 89 50 4E 47
        if (data[0] == (byte) 0x89 && data[1] == (byte) 0x50 && data[2] == (byte) 0x4E && data[3] == (byte) 0x47) {
            return "png";
        }

        // JPEG: FF D8 FF
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) {
            return "jpg";
        }

        // GIF: 47 49 46 38
        if (data[0] == (byte) 0x47 && data[1] == (byte) 0x49 && data[2] == (byte) 0x46 && data[3] == (byte) 0x38) {
            return "gif";
        }

        // WebP: 52 49 46 46 ... 57 45 42 50
        // RIFF....WEBP
        if (data.length >= 12 && data[0] == (byte) 0x52 && data[1] == (byte) 0x49 &&
            data[2] == (byte) 0x46 && data[3] == (byte) 0x46 &&
            data[8] == (byte) 0x57 && data[9] == (byte) 0x45 &&
            data[10] == (byte) 0x42 && data[11] == (byte) 0x50) {
            return "webp";
        }

        // BMP: 42 4D
        if (data[0] == (byte) 0x42 && data[1] == (byte) 0x4D) {
            return "bmp";
        }

        // Check if it's HTML (common error case - server returned an error page)
        String start = new String(data, 0, Math.min(100, data.length)).trim().toLowerCase();
        if (start.startsWith("<!doctype") || start.startsWith("<html") || start.startsWith("<body")) {
            SimplyScreens.LOGGER.warn("Downloaded content appears to be HTML, not an image. The URL may be incorrect or require authentication.");
            return null;
        }

        SimplyScreens.LOGGER.warn("Unknown image format. First4 bytes: {} {} {} {}",
            String.format("%02X", data[0]), String.format("%02X", data[1]),
            String.format("%02X", data[2]), String.format("%02X", data[3]));
        return null;
    }


    private static Path getImagesDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("simply_screens_images");
    }

    public static ImageMetadata getImageMetadata(MinecraftServer server, UUID imageId) {
        Path imagesDir = getImagesDir(server);
        File metadataFile = imagesDir.resolve(imageId + ".json").toFile();

        if (metadataFile.exists()) {
            try (FileReader reader = new FileReader(metadataFile)) {
                return GSON.fromJson(reader, ImageMetadata.class);
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to read image metadata for " + imageId, e);
            }
        }

        return null;
    }

    public static byte[] getImageData(MinecraftServer server, UUID imageId) {
        Path imagePath = getImageFilePath(server, imageId);
        if (imagePath == null) return null;
        File imageFile = imagePath.toFile();

        if (imageFile.exists()) {
            try {
                return Files.readAllBytes(imageFile.toPath());
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to read image data for " + imageId, e);
            }
        }

        return null;
    }

    private static boolean validateImageDimensions(byte[] data, String originalName) {
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (imageInputStream == null) {
                return true;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                return true;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInputStream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (pixels > MAX_IMAGE_PIXELS) {
                    SimplyScreens.LOGGER.warn("Rejecting image '{}' because it is {}x{} ({} pixels), over the limit of {} pixels", originalName, width, height, pixels, MAX_IMAGE_PIXELS);
                    return false;
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            SimplyScreens.LOGGER.warn("Could not validate image dimensions for '{}'", originalName, e);
            return false;
        }

        return true;
    }

    public static Path getImageFilePath(MinecraftServer server, UUID imageId) {
        ImageMetadata metadata = getImageMetadata(server, imageId);
        if (metadata == null) {
            return null;
        }

        return getImagesDir(server).resolve(imageId + "." + metadata.getExtension());
    }

    public static List<ImageMetadata> getImageList(MinecraftServer server) {
        Path imagesDir = getImagesDir(server).toAbsolutePath().normalize();
        if (cachedImageList != null && imagesDir.equals(cachedImagesDirectory)) {
            return cachedImageList;
        }

        List<ImageMetadata> imageList = new ArrayList<>();

        if (Files.exists(imagesDir) && Files.isDirectory(imagesDir)) {
            try (Stream<Path> paths = Files.walk(imagesDir)) {
                paths.filter(path -> path.toString().endsWith(".json"))
                        .forEach(path -> {
                            try (FileReader reader = new FileReader(path.toFile())) {
                                imageList.add(GSON.fromJson(reader, ImageMetadata.class));
                            } catch (IOException e) {
                                SimplyScreens.LOGGER.error("Failed to read image metadata", e);
                            }
                        });
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to list images", e);
            }
        }

        cachedImageList = imageList;
        cachedImagesDirectory = imagesDir;
        return imageList;
    }

    public static List<ImageMetadata> getImageListForPlayer(MinecraftServer server, String playerUUID) {
        List<ImageMetadata> allImages = getImageList(server);
        List<ImageMetadata> playerImages = new ArrayList<>();
        for (ImageMetadata image : allImages) {
            if (image.getOwnerUUID() == null || image.getOwnerUUID().equals(playerUUID)) {
                playerImages.add(image);
            }
        }
        return playerImages;
    }

    public static boolean canPlayerAccessImage(MinecraftServer server, UUID imageId, String playerUUID) {
        ImageMetadata metadata = getImageMetadata(server, imageId);
        return metadata != null && (metadata.getOwnerUUID() == null || metadata.getOwnerUUID().equals(playerUUID));
    }

    private static boolean hasStorageCapacity(MinecraftServer server, String ownerUUID, long incomingBytes) {
        long total = 0L, ownerTotal = 0L;
        int ownerCount = 0;
        for (ImageMetadata metadata : getImageList(server)) {
            Path file = getImagesDir(server).resolve(metadata.getId() + "." + metadata.getExtension());
            try {
                long size = Files.exists(file) ? Files.size(file) : 0L;
                total += size;
                if (ownerUUID != null && ownerUUID.equals(metadata.getOwnerUUID())) {
                    ownerTotal += size;
                    ownerCount++;
                }
            } catch (IOException ignored) { }
        }
        return total + incomingBytes <= Config.MAX_STORAGE_TOTAL &&
                ownerTotal + incomingBytes <= Config.MAX_STORAGE_PER_PLAYER &&
                ownerCount < Config.MAX_IMAGES_PER_PLAYER;
    }

    private static class UploadState {
        private final UUID owner;
        private final int totalChunks;
        private final byte[][] chunks;
        private long receivedBytes;
        private String originalName;
        private volatile long lastActivity;

        private UploadState(UUID owner, int totalChunks, long now) {
            this.owner = owner;
            this.totalChunks = totalChunks;
            this.chunks = new byte[totalChunks][];
            this.lastActivity = now;
        }

        private void addChunk(int chunkIndex, byte[] data, String fileName) {
            if (chunks[chunkIndex] != null) {
                receivedBytes -= chunks[chunkIndex].length;
            }

            chunks[chunkIndex] = data;
            receivedBytes += data.length;

            if (fileName != null) {
                originalName = fileName;
            }
        }

        private boolean isComplete() {
            for (byte[] chunk : chunks) {
                if (chunk == null) {
                    return false;
                }
            }
            return true;
        }
    }
}
