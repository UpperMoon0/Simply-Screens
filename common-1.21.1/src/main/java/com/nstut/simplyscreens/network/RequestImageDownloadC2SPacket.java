package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.helpers.ChunkedFileTransfer;
import com.nstut.simplyscreens.helpers.TransferRequestCoordinator;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

public class RequestImageDownloadC2SPacket implements CustomPacketPayload {
    private static final int CHUNK_SIZE = 32768;
    private static final ExecutorService IMAGE_DOWNLOAD_EXECUTOR =
            ChunkedFileTransfer.newDaemonFixedThreadPool(2, "Simply Screens Image Download");
    private static final TransferRequestCoordinator<String> ACTIVE_DOWNLOADS =
            new TransferRequestCoordinator<>(Duration.ofSeconds(30));

    public static final Type<RequestImageDownloadC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "request_image_download_c2s"));
    
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
            MinecraftServer server = player.getServer();
            String transferKey = player.getUUID() + ":" + packet.imageId;
            ACTIVE_DOWNLOADS.tryStart(transferKey,
                    () -> IMAGE_DOWNLOAD_EXECUTOR.execute(() -> sendImageDownload(server, player, packet.imageId, transferKey)));
        });
    }

    private static void sendImageDownload(MinecraftServer server, ServerPlayer player, UUID imageId, String transferKey) {
        ImageMetadata metadata = ServerImageManager.getImageMetadata(server, imageId);
        if (metadata == null) {
            ACTIVE_DOWNLOADS.release(transferKey);
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
