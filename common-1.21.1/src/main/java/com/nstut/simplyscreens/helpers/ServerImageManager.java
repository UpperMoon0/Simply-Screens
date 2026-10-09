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
import com.nstut.simplyscreens.network.UpdateScreenS2CPacket;
import com.nstut.simplyscreens.network.InvalidateImageS2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.nstut.packetweave.api.TransferId;
import com.nstut.packetweave.api.TransferLimits;
import com.nstut.packetweave.api.TransferRegistry;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class ServerImageManager {
    private static final long MAX_IMAGE_PIXELS = 16_777_216L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int UPLOAD_CHUNK_SIZE = com.nstut.packetweave.api.ChunkedTransfer.CHUNK_BYTES;
    private static final long UPLOAD_TTL_MILLIS = 60_000L;
    private static final int MAX_PROCESSING_UPLOADS_PER_PLAYER = 4;
    private static final int MIN_UPLOAD_SIZE = 16;
    private static final Map<TransferId, UploadState> UPLOADS = new ConcurrentHashMap<>();
    private static final TransferRegistry UPLOAD_TRANSFERS = new TransferRegistry(
            new TransferLimits(UPLOAD_CHUNK_SIZE, Config.MAX_UPLOAD_SIZE_LIMIT, 32, 4,
                    128L * 1024 * 1024, Duration.ofMillis(UPLOAD_TTL_MILLIS)), (id, operation) -> true);
    private static long processingBytes;
    private static final long MAX_PROCESSING_BYTES = 128L * 1024 * 1024;
    private static final Map<UUID, Integer> PROCESSING_UPLOADS = new ConcurrentHashMap<>();
    private static final java.util.Set<com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity> LOADED_SCREENS = ConcurrentHashMap.newKeySet();
    public static void trackLoadedScreen(com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity screen) { LOADED_SCREENS.add(screen); }
    public static void untrackLoadedScreen(com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity screen) { LOADED_SCREENS.remove(screen); }
    private static final ExecutorService IMAGE_UPLOAD_EXECUTOR =
            MediaExecutors.newDaemonBoundedThreadPool(2, 64, "Simply Screens Image Upload");
    private static final java.util.concurrent.ScheduledExecutorService UPLOAD_REAPER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Simply Screens Upload Reaper");
                thread.setDaemon(true);
                return thread;
            });
    static {
        UPLOAD_REAPER.scheduleAtFixedRate(ServerImageManager::reapUploads,
                UPLOAD_TTL_MILLIS, UPLOAD_TTL_MILLIS, TimeUnit.MILLISECONDS);
    }

    public static void initTransfers() {
        dev.architectury.event.events.common.PlayerEvent.PLAYER_QUIT.register(player -> cancelUploads(player.getUUID()));
        dev.architectury.event.events.common.LifecycleEvent.SERVER_STOPPED.register(server -> clearUploads());
    }

    private static synchronized void reapUploads() {
        UPLOAD_TRANSFERS.reapExpired();
        UPLOADS.keySet().removeIf(id -> !UPLOAD_TRANSFERS.contains(id));
    }

    public static synchronized void cancelUploads(UUID owner) {
        UPLOAD_TRANSFERS.cancelOwner(owner);
        UPLOADS.keySet().removeIf(id -> id.owner().equals(owner));
    }

    private static synchronized void clearUploads() {
        UPLOAD_TRANSFERS.clear();
        UPLOADS.clear();
    }

    private static void discardUpload(TransferId id) {
        UPLOAD_TRANSFERS.cancel(id);
        UPLOADS.remove(id);
    }

    private static volatile List<ImageMetadata> cachedImageList;
    private static volatile Path cachedImagesDirectory;

    public static synchronized void handleImageChunk(ServerPlayer player, BlockPos blockPos, UUID transactionId, int chunkIndex, int totalChunks, byte[] data, String fileName) {
        TransferId uploadKey = new TransferId("simply_screens_upload", player.getUUID(), transactionId);
        if (!ScreenPacketSecurity.canModify(player, blockPos) || Config.DISABLE_UPLOAD) {
            discardUpload(uploadKey);
            return;
        }
        reapUploads();
        ServerLevel intendedLevel = player.serverLevel();
        UploadState state = UPLOADS.get(uploadKey);
        if (state == null) {
            if (!UPLOAD_TRANSFERS.beginBounded(uploadKey, totalChunks, Config.MAX_UPLOAD_SIZE)) return;
            state = new UploadState(player.getUUID(), intendedLevel, blockPos.immutable(), totalChunks);
            UPLOADS.put(uploadKey, state);
        }
        if (state.level != intendedLevel || !state.blockPos.equals(blockPos) || state.totalChunks != totalChunks
                || !UPLOAD_TRANSFERS.accept(uploadKey, chunkIndex, data)) {
            discardUpload(uploadKey);
            return;
        }
        if (chunkIndex == 0) state.originalName = fileName;
        byte[] completed = UPLOAD_TRANSFERS.complete(uploadKey);
        if (completed == null) return;
        UPLOADS.remove(uploadKey);
        if (completed.length < MIN_UPLOAD_SIZE || !tryAcquireProcessingSlot(player.getUUID(), completed.length)) return;
        state.imageData = completed;
        UploadState finished = state;
        MinecraftServer server = player.getServer();
        String ownerUUID = player.getUUID().toString();
        try {
            IMAGE_UPLOAD_EXECUTOR.execute(() -> finishImageUpload(server, player, blockPos, finished, ownerUUID));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            releaseProcessingSlot(player.getUUID(), completed.length);
            SimplyScreens.LOGGER.warn("Rejecting image upload because the processing queue is full");
        }
    }

    private static void finishImageUpload(MinecraftServer server, ServerPlayer player, BlockPos blockPos, UploadState state, String ownerUUID) {
        try {
            byte[] imageData = state.imageData;

            UUID imageId = saveImage(server, state.originalName, imageData, null, ownerUUID);
            if (imageId != null) {
                List<ImageMetadata> images = getImageListForPlayer(server, ownerUUID);
                server.execute(() -> {
                    if (player.level() == state.level && ScreenPacketSecurity.canModify(player, blockPos) &&
                            state.level.getBlockEntity(blockPos) instanceof com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity screen) {
                        screen.setImageId(imageId);
                    }

                    PacketRegistries.sendToPlayer(player, new UpdateImageListS2CPacket(images));
                });
            }
        } catch (RuntimeException e) {
            SimplyScreens.LOGGER.error("Failed to finish image upload", e);
        } finally {
            releaseProcessingSlot(state.owner, state.imageData.length);
        }
    }

    private static synchronized boolean tryAcquireProcessingSlot(UUID playerId, long bytes) {
        int count = PROCESSING_UPLOADS.getOrDefault(playerId, 0);
        if (count >= MAX_PROCESSING_UPLOADS_PER_PLAYER || bytes > MAX_PROCESSING_BYTES - processingBytes) return false;
        processingBytes += bytes;
        PROCESSING_UPLOADS.put(playerId, count + 1);
        return true;
    }

    private static synchronized void releaseProcessingSlot(UUID playerId, long bytes) {
        processingBytes -= bytes;
        PROCESSING_UPLOADS.computeIfPresent(playerId, (id, count) -> count <= 1 ? null : count - 1);
    }

    public static UUID saveImage(MinecraftServer server, String originalName, byte[] data, String contentType) {
        return saveImage(server, originalName, data, contentType, null);
    }

    public static synchronized UUID saveImage(MinecraftServer server, String originalName, byte[] data, String contentType, String ownerUUID) {
        try {
            originalName = com.nstut.simplyscreens.ImageNameSanitizer.sanitize(originalName);
            if (data == null || data.length > Math.max(Config.MAX_UPLOAD_SIZE, Config.MAX_URL_DOWNLOAD_SIZE)) {
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
                    if (image == null) {
                        SimplyScreens.LOGGER.warn("Rejecting unsupported image format '{}': no decoder is available", extension);
                        return null;
                    }
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", baos);
                    data = baos.toByteArray();
                    extension = "png";
                } catch (IOException e) {
                    SimplyScreens.LOGGER.error("Failed to convert image to PNG", e);
                    return null;
                }
            }

            long finalLimit = Math.max(Config.MAX_UPLOAD_SIZE, Config.MAX_URL_DOWNLOAD_SIZE);
            if (data.length > finalLimit || !hasStorageCapacity(server, ownerUUID, data.length)) {
                SimplyScreens.LOGGER.warn("Rejecting normalized image because it exceeds a transfer or storage quota");
                return null;
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
            String safeOriginalName = originalName;
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
        return deleteImage(server, imageId, requesterUUID, false);
    }

    public static boolean deleteImage(MinecraftServer server, UUID imageId, String requesterUUID, boolean isAdmin) {
        ImageMetadata metadata = getImageMetadata(server, imageId);
        if (metadata == null) {
            return false;
        }

        boolean isOwner = metadata.getOwnerUUID() != null && metadata.getOwnerUUID().equals(requesterUUID);
        boolean isLegacyUnowned = metadata.getOwnerUUID() == null;
        if (!isAdmin && !isOwner) {
            SimplyScreens.LOGGER.warn("Player {} tried to delete image {} owned by {}", requesterUUID, imageId, metadata.getOwnerUUID());
            return false;
        }

        Path imagesDir = getImagesDir(server);
        try {
            Files.deleteIfExists(imagesDir.resolve(imageId + "." + metadata.getExtension()));
            Files.deleteIfExists(imagesDir.resolve(imageId + ".json"));
            cachedImageList = null;
            invalidateScreensUsingImage(server, imageId);
            com.nstut.simplyscreens.ScreenRegistry.removeImageReferences(imageId);
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
                ImageMetadata raw = GSON.fromJson(reader, ImageMetadata.class);
                return ImageMetadata.validateAndNormalize(raw, getImagesDir(server), imageId.toString());
            } catch (Exception e) {
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
                return false;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
            if (!readers.hasNext()) {
                return false;
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
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data));
                if (decoded == null) {
                    SimplyScreens.LOGGER.warn("Rejecting image '{}' because it could not be decoded", originalName);
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
                        .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(path -> {
                            try (FileReader reader = new FileReader(path.toFile())) {
                                ImageMetadata raw = GSON.fromJson(reader, ImageMetadata.class);
                                String expectedId = path.getFileName().toString();
                                if (expectedId.endsWith(".json")) {
                                    expectedId = expectedId.substring(0, expectedId.length() - 5);
                                }
                                ImageMetadata meta = ImageMetadata.validateAndNormalize(raw, imagesDir, expectedId);
                                if (meta != null) {
                                    imageList.add(meta);
                                }
                            } catch (Exception e) {
                                SimplyScreens.LOGGER.error("Failed to read image metadata for {}", path, e);
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

    // Only screen/image metadata stays here; PacketWeave owns all retained chunks and transfer budgets.
    private static class UploadState {
        private final UUID owner;
        private final ServerLevel level;
        private final BlockPos blockPos;
        private final int totalChunks;
        private String originalName;
        private byte[] imageData;

        private UploadState(UUID owner, ServerLevel level, BlockPos blockPos, int totalChunks) {
            this.owner = owner;
            this.level = level;
            this.blockPos = blockPos;
            this.totalChunks = totalChunks;
        }
    }

    private static void invalidateScreensUsingImage(MinecraftServer server, UUID imageId) {
        java.util.Set<BlockPos> affectedPositions = new java.util.HashSet<>();
        for (com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity screen : List.copyOf(LOADED_SCREENS)) {
            if (screen.isAnchor() && imageId.equals(screen.getImageId())) screen.setImageId(null);
        }

        for (String screenId : com.nstut.simplyscreens.ScreenRegistry.getAllScreenIds()) {
            if (imageId.equals(com.nstut.simplyscreens.ScreenRegistry.getImageId(screenId))) {
                for (ServerLevel level : server.getAllLevels()) {
                    affectedPositions.addAll(com.nstut.simplyscreens.ScreenRegistry.getPositionsForScreenId(level, screenId));
                }
            }
        }

        for (BlockPos pos : affectedPositions) {
            for (ServerLevel level : server.getAllLevels()) {
                if (!level.hasChunkAt(pos)) continue;
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity anchor
                        && anchor.isAnchor() && imageId.equals(anchor.getImageId())) {
                    anchor.setImageId(null);
                    for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(new ChunkPos(pos), false)) {
                        PacketRegistries.sendToPlayer(player, new UpdateScreenS2CPacket(
                                pos, pos, null, anchor.isMaintainAspectRatio(), anchor.getScreenId(),
                                anchor.getScreenWidth(), anchor.getScreenHeight()));
                    }
                }
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketRegistries.sendToPlayer(player, new InvalidateImageS2CPacket(imageId));
        }
    }
}
