package com.nstut.simplyscreens.network;

import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;

import java.util.function.Supplier;

public class UploadCompleteS2CPacket implements IPacket {
    public UploadCompleteS2CPacket() {
    }

    public UploadCompleteS2CPacket(FriendlyByteBuf buf) {
    }

    @Override
    public void write(FriendlyByteBuf buf) {
    }

    @Override
    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> ClientPacketHandler.handleUploadComplete());
    }
}