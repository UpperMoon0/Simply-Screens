package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.Config;
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
import java.util.UUID;

public class RequestImageDownloadC2SPacket implements CustomPacketPayload {
    public static final Type<RequestImageDownloadC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "request_image_download_c2s"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestImageDownloadC2SPacket> CODEC = 
            StreamCodec.ofMember(RequestImageDownloadC2SPacket::write, RequestImageDownloadC2SPacket::new);

    private final UUID imageId;
    private final UUID transferId;

    public RequestImageDownloadC2SPacket(UUID imageId) { this(imageId, UUID.randomUUID()); }
    public RequestImageDownloadC2SPacket(UUID imageId, UUID transferId) {
        this.imageId = imageId; this.transferId = transferId;
    }

    public RequestImageDownloadC2SPacket(RegistryFriendlyByteBuf buf) {
        this.imageId = buf.readUUID();
        this.transferId = buf.readUUID();
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(imageId);
        buf.writeUUID(transferId);
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
            UUID imageId = packet.imageId;
            if (!com.nstut.simplyscreens.helpers.ScreenAccessService.canReadImage(player, packet.imageId)) return;
            Path requestedPath = ServerImageManager.getImageFilePath(server, packet.imageId);
            if (requestedPath == null || !Files.isRegularFile(requestedPath)) return;
            com.nstut.simplyscreens.helpers.ImageMetadata metadata = ServerImageManager.getImageMetadata(server, imageId);
            if (metadata == null) return;
            boolean admitted = com.nstut.packetweave.api.OutboundTransfers.shared().start(player, packet.transferId, requestedPath,
                    Math.max(Config.MAX_UPLOAD_SIZE, Config.MAX_URL_DOWNLOAD_SIZE), server::execute,
                    (index, total, chunk) -> {
                        if (server.getPlayerList().getPlayer(player.getUUID()) != player
                                || !com.nstut.simplyscreens.helpers.ScreenAccessService.canReadImage(player, imageId))
                            throw new java.io.IOException("Image connection or access changed");
                        PacketRegistries.sendToPlayer(player, new ImageDownloadChunkS2CPacket(imageId, packet.transferId,
                                index, total, chunk, index == 0 ? metadata.getExtension() : null));
                    }, failure -> {
                        if (failure != null) server.execute(() -> {
                            if (server.getPlayerList().getPlayer(player.getUUID()) == player)
                                PacketRegistries.sendToPlayer(player, new ImageDownloadChunkS2CPacket(imageId, packet.transferId, -1, 0, new byte[0], null));
                        });
                    });
            if (!admitted) PacketRegistries.sendToPlayer(player, new ImageDownloadChunkS2CPacket(imageId, packet.transferId, -1, 0, new byte[0], null));
        });
    }

}
