package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public class ImageDownloadChunkS2CPacket implements CustomPacketPayload {
    public static final Type<ImageDownloadChunkS2CPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "image_download_chunk_s2c"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, ImageDownloadChunkS2CPacket> CODEC = 
            StreamCodec.ofMember(ImageDownloadChunkS2CPacket::write, ImageDownloadChunkS2CPacket::new);

    private final UUID imageId;
    private final int chunkIndex;
    private final int totalChunks;
    private final byte[] data;
    private final String extension;

    public ImageDownloadChunkS2CPacket(UUID imageId, int chunkIndex, int totalChunks, byte[] data, String extension) {
        this.imageId = imageId;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.data = data;
        this.extension = extension;
    }

    public ImageDownloadChunkS2CPacket(RegistryFriendlyByteBuf buf) {
        this.imageId = buf.readUUID();
        this.chunkIndex = buf.readVarInt();
        this.totalChunks = buf.readVarInt();
        this.data = buf.readByteArray();
        if (this.chunkIndex == 0) {
            this.extension = buf.readUtf();
        } else {
            this.extension = null;
        }
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(imageId);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(totalChunks);
        buf.writeByteArray(data);
        if (chunkIndex == 0) {
            buf.writeUtf(extension);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public UUID getImageId() {
        return imageId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public byte[] getData() {
        return data;
    }

    public String getExtension() {
        return extension;
    }

    public static void handle(ImageDownloadChunkS2CPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ClientImageManager.handleImageChunk(packet.imageId, packet.chunkIndex, packet.totalChunks, packet.data, packet.extension);
        });
    }
}

