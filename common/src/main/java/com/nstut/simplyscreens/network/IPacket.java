package com.nstut.simplyscreens.network;

import dev.architectury.networking.NetworkManager;
import net.minecraft.network.FriendlyByteBuf;

import java.util.function.Supplier;

public interface IPacket {
    void write(FriendlyByteBuf buf);

    void handle(Supplier<NetworkManager.PacketContext> context);
}