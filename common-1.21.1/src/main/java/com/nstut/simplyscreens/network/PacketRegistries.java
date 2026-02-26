package com.nstut.simplyscreens.network;

import dev.architectury.networking.NetworkManager;
import net.minecraft.server.level.ServerPlayer;

public class PacketRegistries {
    public static void register() {
        // Register client to server packets
        NetworkManager.registerReceiver(NetworkManager.c2s(),
            UpdateScreenSelectedImageC2SPacket.TYPE,
            UpdateScreenSelectedImageC2SPacket.CODEC,
            (packet, context) -> UpdateScreenSelectedImageC2SPacket.handle(packet, context));

        NetworkManager.registerReceiver(NetworkManager.c2s(),
            UpdateScreenAspectRatioC2SPacket.TYPE,
            UpdateScreenAspectRatioC2SPacket.CODEC,
            (packet, context) -> UpdateScreenAspectRatioC2SPacket.handle(packet, context));

        NetworkManager.registerReceiver(NetworkManager.c2s(),
            UpdateScreenIdC2SPacket.TYPE,
            UpdateScreenIdC2SPacket.CODEC,
            (packet, context) -> UpdateScreenIdC2SPacket.handle(packet, context));

        NetworkManager.registerReceiver(NetworkManager.c2s(),
            RequestImageDownloadC2SPacket.TYPE,
            RequestImageDownloadC2SPacket.CODEC,
            (packet, context) -> RequestImageDownloadC2SPacket.handle(packet, context));
            
        NetworkManager.registerReceiver(NetworkManager.c2s(),
            RequestImageListC2SPacket.TYPE,
            RequestImageListC2SPacket.CODEC,
            (packet, context) -> RequestImageListC2SPacket.handle(packet, context));
            
        NetworkManager.registerReceiver(NetworkManager.c2s(),
            UploadImageChunkC2SPacket.TYPE,
            UploadImageChunkC2SPacket.CODEC,
            (packet, context) -> UploadImageChunkC2SPacket.handle(packet, context));
    }
    
    public static void registerS2CPackets() {
        // Register server to client packets (client-side only)
        NetworkManager.registerReceiver(NetworkManager.s2c(),
            UpdateScreenS2CPacket.TYPE,
            UpdateScreenS2CPacket.CODEC,
            (packet, context) -> UpdateScreenS2CPacket.handle(packet, context));
            
        NetworkManager.registerReceiver(NetworkManager.s2c(),
            UpdateImageListS2CPacket.TYPE,
            UpdateImageListS2CPacket.CODEC,
            (packet, context) -> UpdateImageListS2CPacket.handle(packet, context));
            
        NetworkManager.registerReceiver(NetworkManager.s2c(),
            ImageDownloadChunkS2CPacket.TYPE,
            ImageDownloadChunkS2CPacket.CODEC,
            (packet, context) -> ImageDownloadChunkS2CPacket.handle(packet, context));
    }
    
    public static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void sendToPlayer(ServerPlayer player, T message) {
        NetworkManager.sendToPlayer(player, message);
    }
    
    public static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void sendToPlayers(Iterable<ServerPlayer> players, T message) {
        for (ServerPlayer player : players) {
            sendToPlayer(player, message);
        }
    }
    
    public static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void sendToServer(T message) {
        NetworkManager.sendToServer(message);
    }
}