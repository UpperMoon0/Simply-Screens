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
    private final boolean maintainAspectRatio;

    public UpdateScreenC2SPacket(BlockPos pos, String imagePath, boolean maintainAspectRatio) {
        this.pos = pos;
        this.imagePath = imagePath;
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public UpdateScreenC2SPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.imagePath = buffer.readUtf(32767);
        this.maintainAspectRatio = buffer.readBoolean();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeUtf(imagePath);
        buffer.writeBoolean(maintainAspectRatio);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer sender = context.get().getSender(); // Get the sender
            if (sender != null) { // Ensure sender is not null
                ServerLevel level = sender.serverLevel(); // Get the server level
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                    BlockPos anchorPos = screenBlockEntity.getAnchorPos();
                    if (anchorPos != null) {
                        BlockEntity anchorBlockEntity = level.getBlockEntity(anchorPos);
                        if (anchorBlockEntity instanceof ScreenBlockEntity anchorScreenBlockEntity) {
                            anchorScreenBlockEntity.updateFromScreenInputs(imagePath, maintainAspectRatio);
                        }
                    }
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}
