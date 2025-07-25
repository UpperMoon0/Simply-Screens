package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.helpers.ServerImageCache;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Supplier;

public class RequestImageUploadC2SPacket {
    public static final ResourceLocation ID = new ResourceLocation(SimplyScreens.MOD_ID, "request_image_upload");

    private final String imageHash;
    private final String imageExtension;
    private final BlockPos blockPos;

    public RequestImageUploadC2SPacket(String imageHash, String imageExtension, BlockPos blockPos) {
        this.imageHash = imageHash;
        this.imageExtension = imageExtension;
        this.blockPos = blockPos;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(imageHash);
        buf.writeUtf(imageExtension);
        buf.writeBlockPos(blockPos);
    }

    public static RequestImageUploadC2SPacket read(FriendlyByteBuf buf) {
        return new RequestImageUploadC2SPacket(buf.readUtf(), buf.readUtf(), buf.readBlockPos());
    }

    public static void apply(RequestImageUploadC2SPacket msg, Supplier<NetworkManager.PacketContext> context) {
        ServerPlayer player = (ServerPlayer) context.get().getPlayer();
        context.get().queue(() -> ServerImageCache.handleRequestImageUpload(msg, player));
    }

    public String getImageHash() {
        return imageHash;
    }

    public String getImageExtension() {
        return imageExtension;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }
}