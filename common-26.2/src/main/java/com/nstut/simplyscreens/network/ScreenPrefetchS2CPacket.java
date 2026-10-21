package com.nstut.simplyscreens.network;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
public final class ScreenPrefetchS2CPacket implements CustomPacketPayload {
    public static final Type<ScreenPrefetchS2CPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "screen_prefetch_s2c"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenPrefetchS2CPacket> CODEC = StreamCodec.ofMember(ScreenPrefetchS2CPacket::write, ScreenPrefetchS2CPacket::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public final BlockPos pos;
    public final String json;
    public ScreenPrefetchS2CPacket(BlockPos pos, String json) {
        this.pos = pos;
        this.json = json;
    }
    public ScreenPrefetchS2CPacket(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        json = buf.readUtf(128);
    }
    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(json, 128);
    }
    public static void handle(ScreenPrefetchS2CPacket packet, NetworkManager.PacketContext context) { context.queue(() -> { ClientPacketHandler.handleScreenPrefetch(packet.pos, packet.json); }); }
}
