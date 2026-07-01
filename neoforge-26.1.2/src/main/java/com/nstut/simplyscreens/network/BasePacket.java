package com.nstut.simplyscreens.network;

import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public abstract class BasePacket<T extends BasePacket<T>> implements CustomPacketPayload {
    private final Type<T> type;

    protected BasePacket(Type<T> type) {
        this.type = type;
    }

    @Override
    public Type<T> type() {
        return type;
    }

    public abstract void write(RegistryFriendlyByteBuf buf);

    public abstract static class BasePacketHandler<T extends BasePacket<T>> {
        public abstract void handle(T packet, NetworkManager.PacketContext context);
    }
}
