package com.nstut.simplyscreens.network;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
public final class ScreenPlaylistC2SPacket implements CustomPacketPayload {
    public static final Type<ScreenPlaylistC2SPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "screen_playlist_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenPlaylistC2SPacket> CODEC = StreamCodec.ofMember(ScreenPlaylistC2SPacket::write, ScreenPlaylistC2SPacket::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public final BlockPos pos;
    public final String json;
    public ScreenPlaylistC2SPacket(BlockPos pos, String json) {
        this.pos = pos;
        this.json = json;
    }
    public ScreenPlaylistC2SPacket(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        json = buf.readUtf(com.nstut.simplyscreens.playlist.PlaylistWire.REQUEST_LIMIT);
    }
    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(json, com.nstut.simplyscreens.playlist.PlaylistWire.REQUEST_LIMIT);
    }
    public static void handle(ScreenPlaylistC2SPacket packet, NetworkManager.PacketContext context) { context.queue(() -> { com.nstut.simplyscreens.helpers.ScreenPlaylistRuntime.handle((net.minecraft.server.level.ServerPlayer) context.getPlayer(), packet.pos, packet.json); }); }
}
