package com.nstut.simplyscreens.network;

import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.function.Supplier;
import dev.architectury.networking.NetworkManager;

public class UpdateScreenIdC2SPacket {
    private final BlockPos pos;
    private final String screenId;

    public UpdateScreenIdC2SPacket(BlockPos pos, String screenId) {
        this.pos = pos;
        this.screenId = screenId;
    }

    public UpdateScreenIdC2SPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        screenId = buf.readUtf();
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(screenId);
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
                    anchor.setScreenId(screenId);

                    // Also update the registry with the current image ID
                    if (screenId != null && !screenId.isEmpty() && anchor.getImageId() != null) {
                        ScreenRegistry.setImageId(screenId, anchor.getImageId());
                        ScreenRegistry.saveRegistry();
                    }
                }
            }
        });
    }
}
