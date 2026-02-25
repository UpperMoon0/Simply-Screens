package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Supplier;

public class UploadImageChunkC2SPacket {
    private final BlockPos blockPos;
    private final UUID transactionId;
    private final int chunkIndex;
    private final int totalChunks;
    private final byte[] data;
    private final String fileName;

    public UploadImageChunkC2SPacket(BlockPos blockPos, UUID transactionId, int chunkIndex, int totalChunks, byte[] data, String fileName) {
        this.blockPos = blockPos;
        this.transactionId = transactionId;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.data = data;
        this.fileName = fileName;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(blockPos);
        buf.writeUUID(transactionId);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(totalChunks);
        buf.writeByteArray(data);
        if (chunkIndex == 0) {
            buf.writeUtf(fileName);
        }
    }

    public static UploadImageChunkC2SPacket read(FriendlyByteBuf buf) {
        BlockPos blockPos = buf.readBlockPos();
        UUID transactionId = buf.readUUID();
        int chunkIndex = buf.readVarInt();
        int totalChunks = buf.readVarInt();
        byte[] data = buf.readByteArray();
        String fileName = null;
        if (chunkIndex == 0) {
            fileName = buf.readUtf();
        }
        return new UploadImageChunkC2SPacket(blockPos, transactionId, chunkIndex, totalChunks, data, fileName);
    }

    public static void apply(UploadImageChunkC2SPacket msg, Supplier<NetworkManager.PacketContext> context) {
        ServerPlayer player = (ServerPlayer) context.get().getPlayer();
        context.get().queue(() -> {
            ServerImageManager.handleImageChunk(player, msg.blockPos, msg.transactionId, msg.chunkIndex, msg.totalChunks, msg.data, msg.fileName);
        });
    }
}