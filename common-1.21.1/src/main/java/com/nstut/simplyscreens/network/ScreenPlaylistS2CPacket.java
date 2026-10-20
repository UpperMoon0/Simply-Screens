package com.nstut.simplyscreens.network;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public final class ScreenPlaylistS2CPacket implements CustomPacketPayload {
    public static final Type<ScreenPlaylistS2CPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "screen_playlist_s2c"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenPlaylistS2CPacket> CODEC = StreamCodec.ofMember(ScreenPlaylistS2CPacket::write, ScreenPlaylistS2CPacket::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public final BlockPos pos;
    public final String json;
    public ScreenPlaylistS2CPacket(BlockPos pos, String json) {
        this.pos = pos;
        this.json = json;
    }
    public ScreenPlaylistS2CPacket(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        json = buf.readUtf(com.nstut.simplyscreens.playlist.PlaylistWire.RESPONSE_LIMIT);
    }
    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(json, com.nstut.simplyscreens.playlist.PlaylistWire.RESPONSE_LIMIT);
    }
    public static void handle(ScreenPlaylistS2CPacket packet, NetworkManager.PacketContext context) { context.queue(() -> { ClientPacketHandler.handleScreenPlaylist(packet.pos, packet.json); }); }
}
