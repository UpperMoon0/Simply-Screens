package com.nstut.simplyscreens.helpers;

import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ScreenPacketSecurity {
    private static final double MAX_INTERACTION_DISTANCE_SQR = 64.0D;
    private ScreenPacketSecurity() {}

    public static boolean canModify(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return false;
        ServerLevel level = player.serverLevel();
        return level.getServer().getPlayerList().getPlayer(player.getUUID()) == player &&
                level.hasChunkAt(pos) && player.mayInteract(level, pos) &&
                player.distanceToSqr(pos.getX() + .5D, pos.getY() + .5D, pos.getZ() + .5D) <= MAX_INTERACTION_DISTANCE_SQR &&
                level.getBlockEntity(pos) instanceof ScreenBlockEntity;
    }
}
