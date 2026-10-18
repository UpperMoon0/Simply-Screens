package com.nstut.simplyscreens.helpers;
import com.nstut.simplyscreens.ScreenNetworkService.Permission;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
public final class ScreenPacketSecurity {
    private ScreenPacketSecurity() { }
    public static boolean canModify(ServerPlayer player, BlockPos pos) { return can(player, pos, Permission.CONTROL); }
    public static boolean canManage(ServerPlayer player, BlockPos pos) { return can(player, pos, Permission.MANAGE); }
    public static ScreenBlockEntity nearby(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return null;
        ServerLevel level = (ServerLevel) player.level();
        if (level.getServer().getPlayerList().getPlayer(player.getUUID()) != player || !level.hasChunkAt(pos)
                || !player.mayInteract(level, pos) || player.distanceToSqr(pos.getX()+.5, pos.getY()+.5, pos.getZ()+.5) > 64) return null;
        return level.getBlockEntity(pos) instanceof ScreenBlockEntity tile ? tile.getAnchorEntity() : null;
    }
    private static boolean can(ServerPlayer player, BlockPos pos, Permission permission) {
        ScreenBlockEntity anchor = nearby(player, pos);
        return anchor != null && ScreenAccessService.allowed(player, anchor, permission);
    }
}
