package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class RequestImageDownloadC2SPacket implements IPacket {
    private static final int CHUNK_SIZE = 1024 * 30; // 30KB
    private static final ExecutorService IMAGE_DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Simply Screens Image Download");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<UUID> loggedWarnings = ConcurrentHashMap.newKeySet();
    private final UUID imageId;

    public RequestImageDownloadC2SPacket(UUID imageId) {
        this.imageId = imageId;
    }

    public RequestImageDownloadC2SPacket(FriendlyByteBuf buf) {
        this.imageId = buf.readUUID();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(imageId);
    }

    @Override
    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> {
            ServerPlayer player = (ServerPlayer) context.get().getPlayer();
            MinecraftServer server = player.getServer();
            IMAGE_DOWNLOAD_EXECUTOR.execute(() -> sendImageDownload(server, player, imageId));
        });
    }

    private static void sendImageDownload(MinecraftServer server, ServerPlayer player, UUID imageId) {
        ImageMetadata metadata = ServerImageManager.getImageMetadata(server, imageId);
        byte[] imageData = ServerImageManager.getImageData(server, imageId);

        if (imageData == null || metadata == null) {
            if (loggedWarnings.add(imageId)) {
                SimplyScreens.LOGGER.warn("Player {} requested non-existent or oversized image {}. This warning will not be shown again.", player.getName().getString(), imageId);
            }
            return;
        }

        String ext = metadata.getExtension();
        if (!"png".equals(ext)) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
                if (image != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", baos);
                    imageData = baos.toByteArray();
                    ext = "png";
                }
            } catch (IOException e) {
                SimplyScreens.LOGGER.warn("Could not convert image {} to PNG, sending original format", imageId);
            }
        }

        byte[] finalImageData = imageData;
        String finalExt = ext;
        server.execute(() -> sendImageChunks(player, imageId, finalImageData, finalExt));
    }

    private static void sendImageChunks(ServerPlayer player, UUID imageId, byte[] imageData, String ext) {
        int totalChunks = (int) Math.ceil((double) imageData.length / CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(imageData.length, start + CHUNK_SIZE);
            byte[] chunk = new byte[end - start];
            System.arraycopy(imageData, start, chunk, 0, chunk.length);

            PacketRegistries.CHANNEL.sendToPlayer(player, new ImageDownloadChunkS2CPacket(imageId, i, totalChunks, chunk, i == 0 ? ext : null));
        }
    }
}
