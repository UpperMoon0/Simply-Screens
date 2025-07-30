package com.nstut.simplyscreens.helpers;

import com.nstut.simplyscreens.DisplayMode;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClientImageCache {
    private static final int CHUNK_SIZE = 1024 * 32; // 32 KB
    private static final Map<String, byte[][]> CHUNK_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> CHUNKS_RECEIVED = new ConcurrentHashMap<>();
    private static final Map<String, Runnable> PENDING_DOWNLOADS = new ConcurrentHashMap<>();

    public static void sendImageToServer(Path imagePath, BlockPos blockPos, boolean maintainAspectRatio, Runnable onComplete) {
        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(imagePath);
            String imageName = imagePath.getFileName().toString();
            sendImageToServer(imageName, imageData, blockPos, maintainAspectRatio, DisplayMode.LOCAL, null, onComplete);
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to read image", e);
        }
    }

    public static void sendImageToServer(String imageName, byte[] imageData, BlockPos blockPos, boolean maintainAspectRatio, DisplayMode displayMode, String url, Runnable onComplete) {
        String imageId = UUID.randomUUID().toString();
        String imageExtension = com.google.common.io.Files.getFileExtension(imageName);
        PacketRegistries.CHANNEL.sendToServer(new RequestImageUploadC2SPacket(imageName, imageId, imageExtension, blockPos, maintainAspectRatio, displayMode, url));

        sendImageInChunks(imageId, imageData);

        if (onComplete != null) {
            PENDING_DOWNLOADS.put(imageId, onComplete);
        }
    }

    private static void sendImageInChunks(String imageId, byte[] imageData) {
        int totalChunks = (int) Math.ceil((double) imageData.length / CHUNK_SIZE);

        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(imageData.length, start + CHUNK_SIZE);
            byte[] chunk = Arrays.copyOfRange(imageData, start, end);
            PacketRegistries.CHANNEL.sendToServer(new ImageChunkC2SPacket(imageId, i, totalChunks, chunk));
        }
    }

    public static void handleImageChunk(ImageChunkS2CPacket msg) {
        CHUNK_CACHE.computeIfAbsent(msg.getImageId(), k -> new byte[msg.getTotalChunks()][]);
        CHUNK_CACHE.get(msg.getImageId())[msg.getChunkIndex()] = msg.getData();

        int receivedCount = CHUNKS_RECEIVED.merge(msg.getImageId(), 1, Integer::sum);

        if (receivedCount == msg.getTotalChunks()) {
            reassembleAndSaveImage(msg.getImageId());
        }
    }

    private static void reassembleAndSaveImage(String fullImageId) {
        byte[][] chunks = CHUNK_CACHE.get(fullImageId);
        if (chunks == null) return;

        String imageId = fullImageId.substring(0, fullImageId.lastIndexOf('.'));
        String imageExtension = fullImageId.substring(fullImageId.lastIndexOf('.') + 1);

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

        File imageFile = getImagePath(imageId, imageExtension).toFile();
        imageFile.getParentFile().mkdirs();

        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            fos.write(imageData);
            if (PENDING_DOWNLOADS.containsKey(imageId)) {
                PENDING_DOWNLOADS.get(imageId).run();
                PENDING_DOWNLOADS.remove(imageId);
            }
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save image " + imageId, e);
        } finally {
            CHUNK_CACHE.remove(fullImageId);
            CHUNKS_RECEIVED.remove(fullImageId);
        }
    }

    public static void handleUpdateScreenWithCachedImage(UpdateScreenWithCachedImageS2CPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockEntity be = mc.level.getBlockEntity(msg.getBlockPos());
        if (be instanceof ScreenBlockEntity screen) {
            String fullId = msg.getImageId();
            String id = "";
            String ext = "";

            if (fullId != null && fullId.contains(".")) {
                id = fullId.substring(0, fullId.lastIndexOf('.'));
                ext = fullId.substring(fullId.lastIndexOf('.') + 1);
            } else {
                id = fullId;
            }

            screen.updateFromCache(id, ext, msg.shouldMaintainAspectRatio());
            mc.level.sendBlockUpdated(msg.getBlockPos(), be.getBlockState(), be.getBlockState(), 3);
        }

        String fullId = msg.getImageId();
        if (fullId != null && fullId.contains(".")) {
            String id = fullId.substring(0, fullId.lastIndexOf('.'));
            if (PENDING_DOWNLOADS.containsKey(id)) {
                PENDING_DOWNLOADS.get(id).run();
                PENDING_DOWNLOADS.remove(id);
            }
        }
    }

    public static Path getImagesDir() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSingleplayer()) {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server != null) {
                return server.getWorldPath(LevelResource.ROOT).resolve("simply_screens_images");
            }
        }
        return mc.gameDirectory.toPath().resolve("simply_screens_images");
    }

    public static Path getImagePath(String imageId, String extension) {
        return getImagesDir().resolve(imageId + "." + extension);
    }

    public static Path getImagePath(String fullImageName) {
        return getImagesDir().resolve(fullImageName);
    }
}