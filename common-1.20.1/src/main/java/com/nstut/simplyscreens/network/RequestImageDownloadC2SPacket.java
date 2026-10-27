package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

public class RequestImageDownloadC2SPacket implements IPacket {
    private final UUID imageId;
    private final UUID transferId;

    public RequestImageDownloadC2SPacket(UUID imageId) { this(imageId, UUID.randomUUID()); }
    public RequestImageDownloadC2SPacket(UUID imageId, UUID transferId) {
        this.imageId = imageId; this.transferId = transferId;
    }

    public RequestImageDownloadC2SPacket(FriendlyByteBuf buf) {
        this.imageId = buf.readUUID();
        this.transferId = buf.readUUID();
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(imageId);
        buf.writeUUID(transferId);
    }

    @Override
    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> {
            ServerPlayer player = (ServerPlayer) context.get().getPlayer();
            MinecraftServer server = player.getServer();
            if (!com.nstut.simplyscreens.helpers.ScreenAccessService.canReadImage(player, imageId)) return;
            Path requestedPath = ServerImageManager.getImageFilePath(server, imageId);
            if (requestedPath == null || !Files.isRegularFile(requestedPath)) return;
            com.nstut.simplyscreens.helpers.ImageMetadata metadata = ServerImageManager.getImageMetadata(server, imageId);
            if (metadata == null) return;
            boolean admitted = com.nstut.packetweave.api.OutboundTransfers.shared().start(player, transferId, requestedPath,
                    Math.max(Config.MAX_UPLOAD_SIZE, Config.MAX_URL_DOWNLOAD_SIZE), server::execute,
                    (index, total, chunk) -> {
                        if (server.getPlayerList().getPlayer(player.getUUID()) != player
                                || !com.nstut.simplyscreens.helpers.ScreenAccessService.canReadImage(player, imageId))
                            throw new java.io.IOException("Image connection or access changed");
                        PacketRegistries.sendToPlayer(player, new ImageDownloadChunkS2CPacket(imageId, transferId,
                                index, total, chunk, index == 0 ? metadata.getExtension() : null));
                    }, failure -> {
                        if (failure != null) server.execute(() -> {
                            if (server.getPlayerList().getPlayer(player.getUUID()) == player)
                                PacketRegistries.sendToPlayer(player, new ImageDownloadChunkS2CPacket(imageId, transferId, -1, 0, new byte[0], null));
                        });
                    });
            if (!admitted) PacketRegistries.sendToPlayer(player, new ImageDownloadChunkS2CPacket(imageId, transferId, -1, 0, new byte[0], null));
        });
    }

}
