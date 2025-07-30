package com.nstut.simplyscreens.helpers;

import com.google.common.io.Files;
import com.nstut.simplyscreens.DisplayMode;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.network.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServerImageCache {
    private static final int CHUNK_SIZE = 1024 * 32; // 32 KB
    private static final Map<String, byte[][]> CHUNK_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> CHUNKS_RECEIVED = new ConcurrentHashMap<>();
    private static final Map<String, BlockPos> PENDING_UPLOADS = new ConcurrentHashMap<>();
    private static final Map<String, String> IMAGE_EXTENSIONS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> PENDING_ASPECT_RATIOS = new ConcurrentHashMap<>();
    private static final Map<String, String> PENDING_IMAGE_NAMES = new ConcurrentHashMap<>();
    private static final Map<String, DisplayMode> PENDING_DISPLAY_MODES = new ConcurrentHashMap<>();
    private static final Map<String, String> PENDING_URLS = new ConcurrentHashMap<>();

    public static void handleRequestImageUpload(RequestImageUploadC2SPacket msg, ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        if (msg.getDisplayMode() == DisplayMode.INTERNET && msg.getUrl() != null) {
            ImageMetadata existingImage = findImageByUrl(server, msg.getUrl());
            if (existingImage != null) {
                if (player.level().getBlockEntity(msg.getBlockPos()) instanceof ScreenBlockEntity screen) {
                    String fullImageId = existingImage.getId() + "." + existingImage.getExtension();
                    screen.updateFromCache(existingImage.getId(), existingImage.getExtension(), msg.isMaintainAspectRatio());
                    broadcastScreenUpdate(screen.getBlockPos(), fullImageId, msg.isMaintainAspectRatio(), player.getServer());
                }
                return;
            }
        }

        File imageFile = getImagePath(server, msg.getImageId(), "png").toFile();

        if (imageFile.exists()) {
            if (player.level().getBlockEntity(msg.getBlockPos()) instanceof ScreenBlockEntity screen) {
                String fullImageId = msg.getImageId() + "." + msg.getImageExtension();
                screen.updateFromCache(msg.getImageId(), msg.getImageExtension(), msg.isMaintainAspectRatio());
                broadcastScreenUpdate(screen.getBlockPos(), fullImageId, msg.isMaintainAspectRatio(), player.getServer());
            }
        } else {
            PENDING_UPLOADS.put(msg.getImageId(), msg.getBlockPos());
            IMAGE_EXTENSIONS.put(msg.getImageId(), msg.getImageExtension());
            PENDING_ASPECT_RATIOS.put(msg.getImageId(), msg.isMaintainAspectRatio());
            PENDING_IMAGE_NAMES.put(msg.getImageId(), msg.getImageName());
            PENDING_DISPLAY_MODES.put(msg.getImageId(), msg.getDisplayMode());
            if (msg.getDisplayMode() == DisplayMode.INTERNET) {
                PENDING_URLS.put(msg.getImageId(), msg.getUrl());
            }
        }
    }

    public static void handleImageChunk(ImageChunkC2SPacket msg, ServerPlayer player) {
        CHUNK_CACHE.computeIfAbsent(msg.getImageId(), k -> new byte[msg.getTotalChunks()][]);
        CHUNK_CACHE.get(msg.getImageId())[msg.getChunkIndex()] = msg.getData();

        int receivedCount = CHUNKS_RECEIVED.merge(msg.getImageId(), 1, Integer::sum);

        if (receivedCount == msg.getTotalChunks()) {
            reassembleAndSaveImage(msg.getImageId(), player.getServer());
        }
    }

    private static void reassembleAndSaveImage(String imageId, MinecraftServer server) {
        byte[][] chunks = CHUNK_CACHE.get(imageId);
        if (chunks == null) return;

        String imageExtension = IMAGE_EXTENSIONS.get(imageId);
        if (imageExtension == null) {
            SimplyScreens.LOGGER.error("Image extension not found for id: " + imageId);
            cleanup(imageId);
            return;
        }

        int totalSize = 0;
        for (byte[] chunk : chunks) {
            totalSize += chunk.length;
        }

        byte[] imageData = new byte[totalSize];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, imageData, offset, chunk.length);
            offset += chunk.length;
        }

        final String finalImageExtension = imageExtension;
        final byte[] finalImageData = imageData;
        final String fullImageId = imageId + "." + finalImageExtension;

        Path imagesDir = getImagesDir(server);
        imagesDir.toFile().mkdirs();
        File imageFile = imagesDir.resolve(fullImageId).toFile();
        File metadataFile = imagesDir.resolve(imageId + ".json").toFile();

        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            fos.write(finalImageData);

            String imageName = PENDING_IMAGE_NAMES.get(imageId);
            String imageNameWithoutExtension = Files.getNameWithoutExtension(imageName);
            DisplayMode displayMode = PENDING_DISPLAY_MODES.getOrDefault(imageId, DisplayMode.LOCAL);
            String url = PENDING_URLS.get(imageId);
            ImageMetadata metadata = new ImageMetadata(imageId, imageNameWithoutExtension, finalImageExtension, System.currentTimeMillis(), displayMode, url);
            try (java.io.FileWriter writer = new java.io.FileWriter(metadataFile)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(metadata, writer);
            }

            BlockPos blockPos = PENDING_UPLOADS.get(imageId);
            boolean maintainAspectRatio = PENDING_ASPECT_RATIOS.getOrDefault(imageId, true);
            if (blockPos != null) {
                server.execute(() -> {
                    for (var level : server.getAllLevels()) {
                        BlockEntity be = level.getBlockEntity(blockPos);
                        if (be instanceof ScreenBlockEntity screen) {
                            screen.updateFromCache(imageId, finalImageExtension, maintainAspectRatio);
                            broadcastScreenUpdate(blockPos, fullImageId, maintainAspectRatio, server);
                            break;
                        }
                    }
                });
            }

            // Notify the client that the image has been uploaded
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PacketRegistries.CHANNEL.sendToPlayer(player, new UpdateScreenWithCachedImageS2CPacket(blockPos, fullImageId, maintainAspectRatio));
            }

        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save image " + imageId, e);
        } finally {
            cleanup(imageId);
        }
    }

    public static void handleRequestImageDownload(RequestImageDownloadC2SPacket msg, ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        File imageFile = getImagePath(server, msg.getImageId()).toFile();

        if (imageFile.exists()) {
            try {
                byte[] imageData = Files.toByteArray(imageFile);
                int totalChunks = (int) Math.ceil((double) imageData.length / CHUNK_SIZE);

                for (int i = 0; i < totalChunks; i++) {
                    int start = i * CHUNK_SIZE;
                    int end = Math.min(imageData.length, start + CHUNK_SIZE);
                    byte[] chunk = Arrays.copyOfRange(imageData, start, end);
                    PacketRegistries.CHANNEL.sendToPlayer(player, new ImageChunkS2CPacket(msg.getImageId(), i, totalChunks, chunk));
                }
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to read image for download: " + msg.getImageId(), e);
            }
        }
    }

    private static void broadcastScreenUpdate(BlockPos blockPos, String imageId, boolean maintainAspectRatio, MinecraftServer server) {
        UpdateScreenWithCachedImageS2CPacket packet = new UpdateScreenWithCachedImageS2CPacket(blockPos, imageId, maintainAspectRatio);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().isLoaded(blockPos)) {
                PacketRegistries.CHANNEL.sendToPlayer(p, packet);
            }
        }
    }

    private static ImageMetadata findImageByUrl(MinecraftServer server, String url) {
        File imagesDir = getImagesDir(server).toFile();
        if (!imagesDir.exists() || !imagesDir.isDirectory()) {
            return null;
        }

        File[] metadataFiles = imagesDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (metadataFiles == null) {
            return null;
        }

        com.google.gson.Gson gson = new com.google.gson.Gson();
        for (File metadataFile : metadataFiles) {
            try (java.io.FileReader reader = new java.io.FileReader(metadataFile)) {
                ImageMetadata metadata = gson.fromJson(reader, ImageMetadata.class);
                if (metadata != null && url.equals(metadata.getUrl())) {
                    return metadata;
                }
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to read metadata file: " + metadataFile.getName(), e);
            }
        }

        return null;
    }

    private static void cleanup(String imageId) {
        CHUNK_CACHE.remove(imageId);
        CHUNKS_RECEIVED.remove(imageId);
        PENDING_UPLOADS.remove(imageId);
        IMAGE_EXTENSIONS.remove(imageId);
        PENDING_ASPECT_RATIOS.remove(imageId);
        PENDING_IMAGE_NAMES.remove(imageId);
        PENDING_DISPLAY_MODES.remove(imageId);
        PENDING_URLS.remove(imageId);
    }

    private static Path getImagesDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("simply_screens_images");
    }

    private static Path getImagePath(MinecraftServer server, String imageId, String extension) {
        return getImagesDir(server).resolve(imageId + "." + extension);
    }

    private static Path getImagePath(MinecraftServer server, String fullImageName) {
        return getImagesDir(server).resolve(fullImageName);
    }
}