package com.nstut.simplyscreens.helpers;

import com.google.common.io.Files;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.network.*;
import dev.architectury.networking.NetworkManager;
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
    private static final Map<String, ServerPlayer> UPLOADING_PLAYERS = new ConcurrentHashMap<>();

    public static void handleRequestImageUpload(RequestImageUploadC2SPacket msg, ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        Path imagesDir = server.getWorldPath(LevelResource.ROOT).resolve("simply_screens_images");
        File imageFile = imagesDir.resolve(msg.getImageHash() + "." + msg.getImageExtension()).toFile();

        if (imageFile.exists()) {
            if (player.level().getBlockEntity(msg.getBlockPos()) instanceof ScreenBlockEntity screen) {
                String fullImageHash = msg.getImageHash() + "." + msg.getImageExtension();
                screen.updateFromCache(fullImageHash, msg.isMaintainAspectRatio());
                broadcastScreenUpdate(screen.getBlockPos(), fullImageHash, msg.isMaintainAspectRatio(), player.getServer());
            }
        } else {
            PENDING_UPLOADS.put(msg.getImageHash(), msg.getBlockPos());
            IMAGE_EXTENSIONS.put(msg.getImageHash(), msg.getImageExtension());
            PENDING_ASPECT_RATIOS.put(msg.getImageHash(), msg.isMaintainAspectRatio());
            UPLOADING_PLAYERS.put(msg.getImageHash(), player);
        }
    }

    public static void handleImageChunk(ImageChunkC2SPacket msg, ServerPlayer player) {
        CHUNK_CACHE.computeIfAbsent(msg.getImageHash(), k -> new byte[msg.getTotalChunks()][]);
        CHUNK_CACHE.get(msg.getImageHash())[msg.getChunkIndex()] = msg.getData();

        int receivedCount = CHUNKS_RECEIVED.merge(msg.getImageHash(), 1, Integer::sum);

        if (receivedCount == msg.getTotalChunks()) {
            reassembleAndSaveImage(msg.getImageHash(), player.getServer());
        }
    }

    private static void reassembleAndSaveImage(String imageHash, MinecraftServer server) {
        byte[][] chunks = CHUNK_CACHE.get(imageHash);
        if (chunks == null) return;

        String imageExtension = IMAGE_EXTENSIONS.get(imageHash);
        if (imageExtension == null) {
            SimplyScreens.LOGGER.error("Image extension not found for hash: " + imageHash);
            cleanup(imageHash);
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

        Path imagesDir = server.getWorldPath(LevelResource.ROOT).resolve("simply_screens_images");
        imagesDir.toFile().mkdirs();
        String fullImageHash = imageHash + "." + imageExtension;
        File imageFile = imagesDir.resolve(fullImageHash).toFile();

        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            fos.write(imageData);

            BlockPos blockPos = PENDING_UPLOADS.get(imageHash);
            boolean maintainAspectRatio = PENDING_ASPECT_RATIOS.getOrDefault(imageHash, true);
            if (blockPos != null) {
                server.execute(() -> {
                    for (var level : server.getAllLevels()) {
                        BlockEntity be = level.getBlockEntity(blockPos);
                        if (be instanceof ScreenBlockEntity screen) {
                            screen.updateFromCache(fullImageHash, maintainAspectRatio);
                            broadcastScreenUpdate(blockPos, fullImageHash, maintainAspectRatio, server);
                            break;
                        }
                    }
                });
            }

            // Notify the client that the upload is complete
            ServerPlayer uploadingPlayer = UPLOADING_PLAYERS.get(imageHash);
            if (uploadingPlayer != null) {
                PacketRegistries.CHANNEL.sendToPlayer(uploadingPlayer, new UploadCompleteS2CPacket());
            }

        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save image " + imageHash, e);
        } finally {
            cleanup(imageHash);
        }
    }

    public static void handleRequestImageDownload(RequestImageDownloadC2SPacket msg, ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        Path imagesDir = server.getWorldPath(LevelResource.ROOT).resolve("simply_screens_images");
        File imageFile = imagesDir.resolve(msg.getImageHash()).toFile();

        if (imageFile.exists()) {
            try {
                byte[] imageData = Files.toByteArray(imageFile);
                int totalChunks = (int) Math.ceil((double) imageData.length / CHUNK_SIZE);

                for (int i = 0; i < totalChunks; i++) {
                    int start = i * CHUNK_SIZE;
                    int end = Math.min(imageData.length, start + CHUNK_SIZE);
                    byte[] chunk = Arrays.copyOfRange(imageData, start, end);
                    PacketRegistries.CHANNEL.sendToPlayer(player, new ImageChunkS2CPacket(msg.getImageHash(), i, totalChunks, chunk));
                }
            } catch (IOException e) {
                SimplyScreens.LOGGER.error("Failed to read image for download: " + msg.getImageHash(), e);
            }
        }
    }

    private static void broadcastScreenUpdate(BlockPos blockPos, String imageHash, boolean maintainAspectRatio, MinecraftServer server) {
        UpdateScreenWithCachedImageS2CPacket packet = new UpdateScreenWithCachedImageS2CPacket(blockPos, imageHash, maintainAspectRatio);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().isLoaded(blockPos)) {
                PacketRegistries.CHANNEL.sendToPlayer(p, packet);
            }
        }
    }

    private static void cleanup(String imageHash) {
        CHUNK_CACHE.remove(imageHash);
        CHUNKS_RECEIVED.remove(imageHash);
        PENDING_UPLOADS.remove(imageHash);
        IMAGE_EXTENSIONS.remove(imageHash);
        PENDING_ASPECT_RATIOS.remove(imageHash);
        UPLOADING_PLAYERS.remove(imageHash);
    }
}