package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageManager;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class UploadImageChunkC2SPacket implements CustomPacketPayload {
    public static final Type<UploadImageChunkC2SPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "upload_image_chunk_c2s"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, UploadImageChunkC2SPacket> CODEC = 
            StreamCodec.ofMember(UploadImageChunkC2SPacket::write, UploadImageChunkC2SPacket::new);

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

    public UploadImageChunkC2SPacket(RegistryFriendlyByteBuf buf) {
        this.blockPos = buf.readBlockPos();
        this.transactionId = buf.readUUID();
        this.chunkIndex = buf.readVarInt();
        this.totalChunks = buf.readVarInt();
        this.data = buf.readByteArray();
        if (this.chunkIndex == 0) {
            this.fileName = buf.readUtf();
        } else {
            this.fileName = null;
        }
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(blockPos);
        buf.writeUUID(transactionId);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(totalChunks);
        buf.writeByteArray(data);
        if (chunkIndex == 0) {
            buf.writeUtf(fileName);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public UUID getTransactionId() {
        return transactionId;
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

    public String getFileName() {
        return fileName;
    }

    public static void handle(UploadImageChunkC2SPacket packet, NetworkManager.PacketContext context) {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        context.queue(() -> {
            ServerImageManager.handleImageChunk(
                    player, packet.blockPos, packet.transactionId, packet.chunkIndex, 
                    packet.totalChunks, packet.data, packet.fileName);
        });
    }
}