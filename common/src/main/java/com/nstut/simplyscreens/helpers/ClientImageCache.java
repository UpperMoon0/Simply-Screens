package com.nstut.simplyscreens.helpers;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientImageCache {
    private static final int CHUNK_SIZE = 1024 * 32; // 32 KB
    private static final Map<String, byte[][]> CHUNK_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> CHUNKS_RECEIVED = new ConcurrentHashMap<>();
    private static final Map<String, Runnable> PENDING_DOWNLOADS = new ConcurrentHashMap<>();

    public static void sendImageToServer(Path imagePath, BlockPos blockPos, boolean maintainAspectRatio, Runnable onComplete) {
        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(imagePath);
            String imageHash = Hashing.sha1().hashBytes(imageData).toString();
            String imageExtension = Files.getFileExtension(imagePath.getFileName().toString());

            PacketRegistries.CHANNEL.sendToServer(new RequestImageUploadC2SPacket(imageHash, imageExtension, blockPos, maintainAspectRatio));

            int totalChunks = (int) Math.ceil((double) imageData.length / CHUNK_SIZE);
            for (int i = 0; i < totalChunks; i++) {
                int start = i * CHUNK_SIZE;
                int end = Math.min(imageData.length, start + CHUNK_SIZE);
                byte[] chunk = Arrays.copyOfRange(imageData, start, end);
                PacketRegistries.CHANNEL.sendToServer(new ImageChunkC2SPacket(imageHash, i, totalChunks, chunk));
            }

            PENDING_DOWNLOADS.put(imageHash + "." + imageExtension, onComplete);
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to read or send image", e);
        }
    }

    public static void handleImageChunk(ImageChunkS2CPacket msg) {
        CHUNK_CACHE.computeIfAbsent(msg.getImageHash(), k -> new byte[msg.getTotalChunks()][]);
        CHUNK_CACHE.get(msg.getImageHash())[msg.getChunkIndex()] = msg.getData();

        int receivedCount = CHUNKS_RECEIVED.merge(msg.getImageHash(), 1, Integer::sum);

        if (receivedCount == msg.getTotalChunks()) {
            reassembleAndSaveImage(msg.getImageHash());
        }
    }

    private static void reassembleAndSaveImage(String imageHash) {
        byte[][] chunks = CHUNK_CACHE.get(imageHash);
        if (chunks == null) return;

        String imageExtension = Files.getFileExtension(imageHash);
        String imageName = imageHash.substring(0, imageHash.lastIndexOf('.'));

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

        File imageFile = getImagePath(imageName + "." + imageExtension).toFile();
        imageFile.getParentFile().mkdirs();

        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            fos.write(imageData);
            if (PENDING_DOWNLOADS.containsKey(imageName)) {
                PENDING_DOWNLOADS.get(imageName).run();
                PENDING_DOWNLOADS.remove(imageName);
            }
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save image " + imageName, e);
        } finally {
            CHUNK_CACHE.remove(imageHash);
            CHUNKS_RECEIVED.remove(imageHash);
        }
    }

    public static void handleUpdateScreenWithCachedImage(UpdateScreenWithCachedImageS2CPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
    
        BlockEntity be = mc.level.getBlockEntity(msg.getBlockPos());
        if (be instanceof ScreenBlockEntity screen) {
            screen.setImageUrl(msg.getImageHash());
            screen.setMaintainAspectRatio(msg.shouldMaintainAspectRatio());
            screen.setChanged();
            mc.level.sendBlockUpdated(msg.getBlockPos(), be.getBlockState(), be.getBlockState(), 3);
        }
    
        if (PENDING_DOWNLOADS.containsKey(msg.getImageHash())) {
            PENDING_DOWNLOADS.get(msg.getImageHash()).run();
            PENDING_DOWNLOADS.remove(msg.getImageHash());
        }
    }

    public static Path getImagePath(String imageHash) {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("simply_screens_cache").resolve(imageHash);
    }
}