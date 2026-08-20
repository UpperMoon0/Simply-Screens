package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.helpers.ChunkedFileTransfer;
import com.nstut.simplyscreens.helpers.TransferRequestCoordinator;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public class RequestImageDownloadC2SPacket implements IPacket {
    private static final int CHUNK_SIZE = 1024 * 30; // 30KB
    private static final ExecutorService IMAGE_DOWNLOAD_EXECUTOR =
            ChunkedFileTransfer.newDaemonFixedThreadPool(2, "Simply Screens Image Download");
    private static final TransferRequestCoordinator<String> ACTIVE_DOWNLOADS =
            new TransferRequestCoordinator<>(Duration.ofSeconds(30));
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
            String transferKey = player.getUUID() + ":" + imageId;
            ACTIVE_DOWNLOADS.tryStart(transferKey,
                    () -> IMAGE_DOWNLOAD_EXECUTOR.execute(() -> sendImageDownload(server, player, imageId, transferKey)));
        });
    }

    private static void sendImageDownload(MinecraftServer server, ServerPlayer player, UUID imageId, String transferKey) {
        ImageMetadata metadata = ServerImageManager.getImageMetadata(server, imageId);
        if (metadata == null) {
            ACTIVE_DOWNLOADS.release(transferKey);
            if (loggedWarnings.add(imageId)) {
                SimplyScreens.LOGGER.warn("Player {} requested non-existent image {}. This warning will not be shown again.", player.getName().getString(), imageId);
            }
            return;
        }

        Path imagePath = ServerImageManager.getImageFilePath(server, imageId);
        if (imagePath == null || !Files.exists(imagePath)) {
            ACTIVE_DOWNLOADS.release(transferKey);
            return;
        }

        try {
            long maxDownloadSize = Math.max(Config.MAX_UPLOAD_SIZE, Config.MAX_URL_DOWNLOAD_SIZE);
            long fileSize = Files.size(imagePath);
            if (fileSize > maxDownloadSize) {
                ACTIVE_DOWNLOADS.release(transferKey);
                SimplyScreens.LOGGER.warn("Refusing to send image {} because it is {} bytes, over the configured limit of {} bytes",
                        imageId, fileSize, maxDownloadSize);
                return;
            }
            ChunkedFileTransfer.streamFile(imagePath, CHUNK_SIZE, (chunkIndex, totalChunks, chunk) -> {
                String packetExt = chunkIndex == 0 ? metadata.getExtension() : null;
                server.execute(() -> PacketRegistries.CHANNEL.sendToPlayer(player, new ImageDownloadChunkS2CPacket(
                        imageId, chunkIndex, totalChunks, chunk, packetExt)));
            });
            ACTIVE_DOWNLOADS.release(transferKey);
        } catch (Exception e) {
            ACTIVE_DOWNLOADS.release(transferKey);
            SimplyScreens.LOGGER.error("Failed to stream image {} to player {}", imageId, player.getName().getString(), e);
        }
    }
}
