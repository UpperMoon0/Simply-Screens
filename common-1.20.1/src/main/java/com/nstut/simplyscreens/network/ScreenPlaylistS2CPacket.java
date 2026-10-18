package com.nstut.simplyscreens.network;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import java.util.function.Supplier;
public final class ScreenPlaylistS2CPacket {
    public final BlockPos pos;
    public final String json;
    public ScreenPlaylistS2CPacket(BlockPos pos, String json) {
        this.pos = pos;
        this.json = json;
    }
    public ScreenPlaylistS2CPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        json = buf.readUtf(com.nstut.simplyscreens.playlist.PlaylistWire.RESPONSE_LIMIT);
    }
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(json, com.nstut.simplyscreens.playlist.PlaylistWire.RESPONSE_LIMIT);
    }
    public void handle(Supplier<NetworkManager.PacketContext> context) { context.get().queue(() -> { ClientPacketHandler.handleScreenPlaylist(this.pos, this.json); }); }
}
