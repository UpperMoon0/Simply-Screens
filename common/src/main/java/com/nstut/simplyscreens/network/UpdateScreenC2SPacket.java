package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.function.Supplier;
import dev.architectury.networking.NetworkManager;

public class UpdateScreenC2SPacket {
    private final BlockPos pos;
    private final UUID imageId;

    public UpdateScreenC2SPacket(BlockPos pos, UUID imageId) {
        this.pos = pos;
        this.imageId = imageId;
    }

    public UpdateScreenC2SPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        imageId = buf.readUUID();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUUID(imageId);
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
                    anchor.setImageId(imageId);
                }
            }
        });
    }
}
