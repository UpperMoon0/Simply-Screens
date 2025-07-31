package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.function.Supplier;

public class UpdateScreenAspectRatioC2SPacket {
    private final BlockPos pos;
    private final boolean maintainAspectRatio;

    public UpdateScreenAspectRatioC2SPacket(BlockPos pos, boolean maintainAspectRatio) {
        this.pos = pos;
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public UpdateScreenAspectRatioC2SPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        maintainAspectRatio = buf.readBoolean();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBoolean(maintainAspectRatio);
    }

    public void handle(Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> {
            ServerPlayer player = (ServerPlayer) context.get().getPlayer();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                ScreenBlockEntity anchor = screenBlockEntity.getAnchorEntity();
                if (anchor != null) {
                    anchor.setMaintainAspectRatio(maintainAspectRatio);
                }
            }
        });
    }
}