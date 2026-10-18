package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.helpers.MediaExecutors;
import com.nstut.simplyscreens.helpers.WorkerTransferCoordinator;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RequestImageDownloadC2SPacket implements CustomPacketPayload {
    private static final int CHUNK_SIZE = com.nstut.packetweave.api.ChunkedTransfer.CHUNK_BYTES;
    private static final ExecutorService IMAGE_DOWNLOAD_EXECUTOR =
            MediaExecutors.newDaemonBoundedThreadPool(2, 64, "Simply Screens Image Download");
    private static final WorkerTransferCoordinator<String> ACTIVE_DOWNLOADS =
            new WorkerTransferCoordinator<>(1024);
    private static final Map<UUID, AtomicInteger> PLAYER_DOWNLOADS = new ConcurrentHashMap<>();
    private static final int MAX_DOWNLOADS_PER_PLAYER = 4;

    public static final Type<RequestImageDownloadC2SPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "request_image_download_c2s"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestImageDownloadC2SPacket> CODEC = 
            StreamCodec.ofMember(RequestImageDownloadC2SPacket::write, RequestImageDownloadC2SPacket::new);

    private final UUID imageId;

    public RequestImageDownloadC2SPacket(UUID imageId) {
        this.imageId = imageId;
    }

    public RequestImageDownloadC2SPacket(RegistryFriendlyByteBuf buf) {
        this.imageId = buf.readUUID();
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(imageId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public UUID getImageId() {
        return imageId;
    }

    public static void handle(RequestImageDownloadC2SPacket packet, NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        context.queue(() -> {
            MinecraftServer server = player.level().getServer();
            if (!com.nstut.simplyscreens.helpers.ScreenAccessService.canReadImage(player, packet.imageId)) return;
            Path requestedPath = ServerImageManager.getImageFilePath(server, packet.imageId);
            if (requestedPath == null || !Files.isRegularFile(requestedPath)) return;
            String transferKey = player.getUUID() + ":" + packet.imageId;
            AtomicInteger playerCount = PLAYER_DOWNLOADS.computeIfAbsent(player.getUUID(), ignored -> new AtomicInteger());
            if (playerCount.incrementAndGet() > MAX_DOWNLOADS_PER_PLAYER) {
                releasePlayerDownload(player.getUUID(), playerCount);
                return;
            }
            try {
                boolean started = ACTIVE_DOWNLOADS.tryStart(transferKey, IMAGE_DOWNLOAD_EXECUTOR, () -> {
                    try { sendImageDownload(server, player, packet.imageId); }
                    finally { releasePlayerDownload(player.getUUID(), playerCount); }
                });
                if (!started) releasePlayerDownload(player.getUUID(), playerCount);
            } catch (RejectedExecutionException exception) {
                releasePlayerDownload(player.getUUID(), playerCount);
            }
        });
    }

    private static void releasePlayerDownload(UUID playerId, AtomicInteger counter) {
        if (counter.decrementAndGet() <= 0) PLAYER_DOWNLOADS.remove(playerId, counter);
    }

    private static void sendImageDownload(MinecraftServer server, ServerPlayer player, UUID imageId) {
        ImageMetadata metadata = ServerImageManager.getImageMetadata(server, imageId);
        if (metadata == null) {
            return;
        }

        Path imagePath = ServerImageManager.getImageFilePath(server, imageId);
        if (imagePath == null || !Files.exists(imagePath)) {
            return;
        }

        try {
            long maxDownloadSize = Math.max(Config.MAX_UPLOAD_SIZE, Config.MAX_URL_DOWNLOAD_SIZE);
            long fileSize = Files.size(imagePath);
            if (fileSize > maxDownloadSize) {
                SimplyScreens.LOGGER.warn("Refusing to send image {} because it is {} bytes, over the configured limit of {} bytes",
                        imageId, fileSize, maxDownloadSize);
                return;
            }
            com.nstut.packetweave.api.ChunkedTransfer.streamFile(imagePath, CHUNK_SIZE, maxDownloadSize,
                    com.nstut.packetweave.api.ChunkedTransfer.onExecutor(server::execute, Duration.ofSeconds(30),
                            (chunkIndex, totalChunks, chunk) -> {
                                if (server.getPlayerList().getPlayer(player.getUUID()) != player)
                                    throw new java.io.IOException("Player disconnected during media transfer");
                                if (!com.nstut.simplyscreens.helpers.ScreenAccessService.canReadImage(player, imageId))
                                    throw new java.io.IOException("Media access revoked");
                                String packetExt = chunkIndex == 0 ? metadata.getExtension() : null;
                                PacketRegistries.sendToPlayer(player, new ImageDownloadChunkS2CPacket(
                                        imageId, chunkIndex, totalChunks, chunk, packetExt));
                            }));
        } catch (Exception e) {
            SimplyScreens.LOGGER.error("Failed to stream image {} to player {}", imageId, player.getName().getString(), e);
        }
    }
}


