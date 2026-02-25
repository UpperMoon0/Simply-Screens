package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ImageMetadata;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class RequestImageDownloadC2SPacket implements CustomPacketPayload {
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
            byte[] imageData = ServerImageManager.getImageData(player.getServer(), packet.imageId);
            if (imageData != null) {
                ImageMetadata metadata = ServerImageManager.getImageMetadata(player.getServer(), packet.imageId);
                if (metadata != null) {
                    // Send the image data in chunks
                    int chunkSize = 32768; // 32KB chunks
                    int totalChunks = (int) Math.ceil((double) imageData.length / chunkSize);
                    
                    for (int i = 0; i < totalChunks; i++) {
                        int start = i * chunkSize;
                        int end = Math.min(start + chunkSize, imageData.length);
                        byte[] chunk = new byte[end - start];
                        System.arraycopy(imageData, start, chunk, 0, chunk.length);
                        
                        PacketRegistries.sendToPlayer(player, new ImageDownloadChunkS2CPacket(
                                packet.imageId, i, totalChunks, chunk, 
                                i == 0 ? metadata.getExtension() : null));
                    }
                }
            }
        });
    }
}