package com.nstut.simplyscreens.network;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import java.util.function.Supplier;
public final class ScreenPlaylistC2SPacket {
    public final BlockPos pos;
    public final String json;
    public ScreenPlaylistC2SPacket(BlockPos pos, String json) {
        this.pos = pos;
        this.json = json;
    }
    public ScreenPlaylistC2SPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        json = buf.readUtf(com.nstut.simplyscreens.playlist.PlaylistWire.REQUEST_LIMIT);
    }
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(json, com.nstut.simplyscreens.playlist.PlaylistWire.REQUEST_LIMIT);
    }
    public void handle(Supplier<NetworkManager.PacketContext> context) { context.get().queue(() -> { com.nstut.simplyscreens.helpers.ScreenPlaylistRuntime.handle((net.minecraft.server.level.ServerPlayer) context.get().getPlayer(), this.pos, this.json); }); }
}
