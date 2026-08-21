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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.function.Supplier;

public class RequestImageDownloadC2SPacket implements IPacket {
    private static final int CHUNK_SIZE = ChunkedFileTransfer.CHUNK_SIZE;
    private static final ExecutorService IMAGE_DOWNLOAD_EXECUTOR =
            ChunkedFileTransfer.newDaemonBoundedThreadPool(2, 64, "Simply Screens Image Download");
    private static final TransferRequestCoordinator<String> ACTIVE_DOWNLOADS =
            new TransferRequestCoordinator<>(Duration.ofSeconds(30));
    private static final Map<UUID, AtomicInteger> PLAYER_DOWNLOADS = new ConcurrentHashMap<>();
    private static final int MAX_DOWNLOADS_PER_PLAYER = 4;
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
            Path requestedPath = ServerImageManager.getImageFilePath(server, imageId);
            if (requestedPath == null || !Files.isRegularFile(requestedPath)) return;
            String transferKey = player.getUUID() + ":" + imageId;
            AtomicInteger playerCount = PLAYER_DOWNLOADS.computeIfAbsent(player.getUUID(), ignored -> new AtomicInteger());
            if (playerCount.incrementAndGet() > MAX_DOWNLOADS_PER_PLAYER) {
                releasePlayerDownload(player.getUUID(), playerCount);
                return;
            }
            try {
                boolean started = ACTIVE_DOWNLOADS.tryStart(transferKey, () -> IMAGE_DOWNLOAD_EXECUTOR.execute(() -> {
                    try { sendImageDownload(server, player, imageId, transferKey); }
                    finally { releasePlayerDownload(player.getUUID(), playerCount); }
                }));
                if (!started) releasePlayerDownload(player.getUUID(), playerCount);
            } catch (RejectedExecutionException exception) {
                releasePlayerDownload(player.getUUID(), playerCount);
            }
        });
    }

    private static void releasePlayerDownload(UUID playerId, AtomicInteger counter) {
        if (counter.decrementAndGet() <= 0) PLAYER_DOWNLOADS.remove(playerId, counter);
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
                server.execute(() -> PacketRegistries.sendToPlayer(player, new ImageDownloadChunkS2CPacket(
                        imageId, chunkIndex, totalChunks, chunk, packetExt)));
            });
            ACTIVE_DOWNLOADS.release(transferKey);
        } catch (Exception e) {
            ACTIVE_DOWNLOADS.release(transferKey);
            SimplyScreens.LOGGER.error("Failed to stream image {} to player {}", imageId, player.getName().getString(), e);
        }
    }
}
