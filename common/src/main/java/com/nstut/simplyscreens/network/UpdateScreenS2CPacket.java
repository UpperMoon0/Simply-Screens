package com.nstut.simplyscreens.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import java.util.UUID;
import java.util.function.Supplier;

public class UpdateScreenS2CPacket {
    private final BlockPos pos;
    private final UUID imageId;

    public UpdateScreenS2CPacket(BlockPos pos, UUID imageId) {
        this.pos = pos;
        this.imageId = imageId;
    }

    public UpdateScreenS2CPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        if (buf.readBoolean()) {
            imageId = buf.readUUID();
        } else {
            imageId = null;
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(imageId != null);
        if (imageId != null) {
            buf.writeUUID(imageId);
        }
    }

    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> ClientPacketHandler.handleUpdateScreen(pos, imageId));
    }
}
