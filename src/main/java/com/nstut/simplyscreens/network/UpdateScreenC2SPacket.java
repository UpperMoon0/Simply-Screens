package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class UpdateScreenC2SPacket {
    private final BlockPos pos;
    private final String imagePath;

    public UpdateScreenC2SPacket(BlockPos pos, String imagePath) {
        this.pos = pos;
        this.imagePath = imagePath;
    }

    public UpdateScreenC2SPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.imagePath = buffer.readUtf(32767); // Read the string from the buffer
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeUtf(imagePath);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer sender = context.get().getSender(); // Get the sender
            if (sender != null) { // Ensure sender is not null
                ServerLevel level = sender.serverLevel(); // Get the server level
                // Ensure level is not null
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                    screenBlockEntity.setImagePath(imagePath); // Update the block entity
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}
