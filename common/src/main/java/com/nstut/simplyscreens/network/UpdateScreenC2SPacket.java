package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.function.Supplier;
import dev.architectury.networking.NetworkManager;

public class UpdateScreenC2SPacket {
    private final BlockPos pos;
    private final String imagePath;
    private final boolean maintainAspectRatio;

    public UpdateScreenC2SPacket(BlockPos pos, String imagePath, boolean maintainAspectRatio) {
        this.pos = pos;
        this.imagePath = imagePath;
        this.maintainAspectRatio = maintainAspectRatio;
    }

    public UpdateScreenC2SPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        imagePath = buf.readUtf();
        maintainAspectRatio = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(imagePath);
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
                BlockPos anchorPos = screenBlockEntity.getAnchorPos();

                if (anchorPos != null) {
                    BlockEntity anchorBlockEntity = level.getBlockEntity(anchorPos);

                    if (anchorBlockEntity instanceof ScreenBlockEntity anchorScreenBlockEntity) {
                        anchorScreenBlockEntity.updateFromScreenInputs(imagePath, maintainAspectRatio);
                    }
                }
            }
        });
    }
}
