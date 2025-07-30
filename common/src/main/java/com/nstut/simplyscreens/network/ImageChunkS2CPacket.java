package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ClientImageCache;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

public class ImageChunkS2CPacket {
    public static final ResourceLocation ID = new ResourceLocation(SimplyScreens.MOD_ID, "image_chunk");

    private final String imageId;
    private final int chunkIndex;
    private final int totalChunks;
    private final byte[] data;

    public ImageChunkS2CPacket(String imageId, int chunkIndex, int totalChunks, byte[] data) {
        this.imageId = imageId;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.data = data;
    }

    public ImageChunkS2CPacket(FriendlyByteBuf buf) {
        this.imageId = buf.readUtf();
        this.chunkIndex = buf.readInt();
        this.totalChunks = buf.readInt();
        this.data = buf.readByteArray();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(imageId);
        buf.writeInt(chunkIndex);
        buf.writeInt(totalChunks);
        buf.writeByteArray(data);
    }

    public static void handle(ImageChunkS2CPacket msg, Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> ClientImageCache.handleImageChunk(msg));
    }

    public String getImageId() {
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
}