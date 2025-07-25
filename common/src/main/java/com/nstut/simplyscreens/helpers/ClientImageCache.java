package com.nstut.simplyscreens.helpers;

import com.google.common.hash.Hashing;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientImageCache {
    private static final int CHUNK_SIZE = 16 * 1024; // 16KB per chunk
    private static final Map<String, byte[][]> INCOMING_CHUNKS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> CHUNKS_RECEIVED = new ConcurrentHashMap<>();

    public static void sendImageToServer(Path imagePath, BlockPos blockPos) {
        try {
            byte[] imageData = Files.readAllBytes(imagePath);
            String imageHash = Hashing.sha256().hashBytes(imageData).toString();
            String extension = FilenameUtils.getExtension(imagePath.getFileName().toString());

            PacketRegistries.CHANNEL.sendToServer(new RequestImageUploadC2SPacket(imageHash, extension, blockPos));

            sendImageInChunks(imageHash, imageData);
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to read image file: " + imagePath, e);
        }
    }

    private static void sendImageInChunks(String imageHash, byte[] imageData) {
        int totalChunks = (int) Math.ceil((double) imageData.length / CHUNK_SIZE);

        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int length = Math.min(imageData.length - start, CHUNK_SIZE);
            byte[] chunk = new byte[length];
            System.arraycopy(imageData, start, chunk, 0, length);

            PacketRegistries.CHANNEL.sendToServer(new ImageChunkC2SPacket(imageHash, i, totalChunks, chunk));
        }
    }


    public static void handleUpdateScreenWithCachedImage(UpdateScreenWithCachedImageS2CPacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Path imagePath = mc.gameDirectory.toPath().resolve("simply_screens_images").resolve(msg.getImageHash());
        if (Files.exists(imagePath)) {
            BlockEntity be = mc.level.getBlockEntity(msg.getBlockPos());
            if (be instanceof ScreenBlockEntity screen) {
                screen.setImageHash(msg.getImageHash());
            }
        } else {
            PacketRegistries.CHANNEL.sendToServer(new RequestImageDownloadC2SPacket(msg.getImageHash()));
        }
    }

    public static void handleImageChunk(ImageChunkS2CPacket msg) {
        INCOMING_CHUNKS.computeIfAbsent(msg.getImageHash(), k -> new byte[msg.getTotalChunks()][]);
        INCOMING_CHUNKS.get(msg.getImageHash())[msg.getChunkIndex()] = msg.getData();

        int receivedCount = CHUNKS_RECEIVED.merge(msg.getImageHash(), 1, Integer::sum);

        if (receivedCount == msg.getTotalChunks()) {
            reassembleAndSaveImage(msg.getImageHash());
        }
    }

    private static void reassembleAndSaveImage(String imageHash) {
        byte[][] chunks = INCOMING_CHUNKS.get(imageHash);
        if (chunks == null) return;

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

        Minecraft mc = Minecraft.getInstance();
        Path imagesDir = mc.gameDirectory.toPath().resolve("simply_screens_images");
        imagesDir.toFile().mkdirs();
        File imageFile = imagesDir.resolve(imageHash).toFile();

        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            fos.write(imageData);
        } catch (IOException e) {
            SimplyScreens.LOGGER.error("Failed to save cached image " + imageHash, e);
        } finally {
            INCOMING_CHUNKS.remove(imageHash);
            CHUNKS_RECEIVED.remove(imageHash);
        }
    }
}