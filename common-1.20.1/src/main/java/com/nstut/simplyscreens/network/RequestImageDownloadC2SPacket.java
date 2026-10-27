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
    private static final java.util.Map<Preparation, ServerPlayer> PREPARING = new java.util.IdentityHashMap<>();
    private static final java.util.concurrent.ExecutorService PREPARE = com.nstut.simplyscreens.helpers.MediaExecutors
            .newDaemonBoundedThreadPool(2, 32, "Simply Screens Download Preparation");
    private static final class Preparation {
        final MinecraftServer server;
        Preparation(MinecraftServer server) { this.server = server; }
    }
    public static void cancelPending(Object player) { synchronized (PREPARING) { PREPARING.values().removeIf(owner -> owner == player); } }
    public static void cancelServer(MinecraftServer server) {
        synchronized (PREPARING) { PREPARING.keySet().removeIf(work -> work.server == server); }
    }
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
            prepare(player, player.getServer());
        });
    }

    private void prepare(ServerPlayer player, MinecraftServer server) {
            if (server == null || !server.isRunning() || server.getPlayerList().getPlayer(player.getUUID()) != player) return;
            Preparation preparation = new Preparation(server);
            synchronized (PREPARING) {
                int peerPreparations = 0;
                for (ServerPlayer owner : PREPARING.values()) if (owner == player) peerPreparations++;
                if (PREPARING.size() >= 32 || peerPreparations >= 2) { reject(player); return; }
                PREPARING.put(preparation, player);
            }
            try {
                PREPARE.execute(() -> {
                    com.nstut.simplyscreens.helpers.ImageMetadata metadata;
                    Path requestedPath;
                    try {
                        metadata = ServerImageManager.getImageMetadata(server, imageId);
                        requestedPath = metadata == null ? null : ServerImageManager.getImageFilePath(server, imageId, metadata);
                        if (requestedPath != null && !Files.isRegularFile(requestedPath)) requestedPath = null;
                    } catch (RuntimeException failure) { metadata = null; requestedPath = null; }
                    var snapshot = metadata;
                    var path = requestedPath;
                    try { server.execute(() -> {
                        try {
                            synchronized (PREPARING) { if (PREPARING.get(preparation) != player) return; }
                            if (server == null || !server.isRunning() || server.getPlayerList().getPlayer(player.getUUID()) != player) return;
                            if (snapshot == null || path == null
                                    || !com.nstut.simplyscreens.helpers.ScreenAccessService.canReadImage(player, imageId, snapshot)) {
                                reject(player); return;
                            }
                            send(player, server, path, snapshot);
                        } finally { synchronized (PREPARING) { PREPARING.remove(preparation, player); } }
                    }); } catch (RuntimeException stopped) {
                        synchronized (PREPARING) { PREPARING.remove(preparation, player); }
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                synchronized (PREPARING) { PREPARING.remove(preparation, player); }
                reject(player);
            }
    }

    private void reject(ServerPlayer player) {
        PacketRegistries.sendToPlayer(player, new ImageDownloadChunkS2CPacket(imageId, transferId, -1, 0, new byte[0], null));
    }

    private void send(ServerPlayer player, MinecraftServer server, Path requestedPath,
                      com.nstut.simplyscreens.helpers.ImageMetadata metadata) {
            boolean admitted = com.nstut.packetweave.api.OutboundTransfers.shared().start(player, transferId, requestedPath,
                    Math.max(Config.MAX_UPLOAD_SIZE, Config.MAX_URL_DOWNLOAD_SIZE), server::execute,
                    (index, total, chunk) -> {
                        if (server.getPlayerList().getPlayer(player.getUUID()) != player
                                || !com.nstut.simplyscreens.helpers.ScreenAccessService.canReadImage(player, imageId, metadata))
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
    }

}
