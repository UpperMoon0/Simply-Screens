package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageCache;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

public class ImageChunkC2SPacket {
    public static final ResourceLocation ID = new ResourceLocation(SimplyScreens.MOD_ID, "image_chunk");

    private final String imageId;
    private final int chunkIndex;
    private final int totalChunks;
    private final byte[] data;

    public ImageChunkC2SPacket(String imageId, int chunkIndex, int totalChunks, byte[] data) {
        this.imageId = imageId;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.data = data;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(imageId);
        buf.writeInt(chunkIndex);
        buf.writeInt(totalChunks);
        buf.writeByteArray(data);
    }

    public static ImageChunkC2SPacket read(FriendlyByteBuf buf) {
        return new ImageChunkC2SPacket(buf.readUtf(), buf.readInt(), buf.readInt(), buf.readByteArray());
    }

    public static void apply(ImageChunkC2SPacket msg, Supplier<NetworkManager.PacketContext> context) {
        ServerPlayer player = (ServerPlayer) context.get().getPlayer();
        context.get().queue(() -> ServerImageCache.handleImageChunk(msg, player));
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