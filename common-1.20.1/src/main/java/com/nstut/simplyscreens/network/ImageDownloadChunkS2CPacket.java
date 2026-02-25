package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.helpers.ClientImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;
import java.util.function.Supplier;

public class ImageDownloadChunkS2CPacket implements IPacket {
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

    public ImageDownloadChunkS2CPacket(FriendlyByteBuf buf) {
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

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(imageId);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(totalChunks);
        buf.writeByteArray(data);
        if (chunkIndex == 0) {
            buf.writeUtf(extension);
        }
    }

    @Override
    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> {
            ClientImageManager.handleImageChunk(imageId, chunkIndex, totalChunks, data, extension);
        });
    }
}