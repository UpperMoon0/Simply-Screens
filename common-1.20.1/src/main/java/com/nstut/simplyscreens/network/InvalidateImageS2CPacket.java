package com.nstut.simplyscreens.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import java.util.UUID;
import java.util.function.Supplier;

public class InvalidateImageS2CPacket {
    public final UUID imageId;

    public InvalidateImageS2CPacket(UUID imageId) {
        this.imageId = imageId;
    }

    public InvalidateImageS2CPacket(FriendlyByteBuf buf) {
        this.imageId = buf.readBoolean() ? buf.readUUID() : null;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(imageId != null);
        if (imageId != null) {
            buf.writeUUID(imageId);
        }
    }

    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> ClientPacketHandler.handleInvalidateImage(imageId));
    }
}
