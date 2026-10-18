package com.nstut.simplyscreens.network;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public final class ScreenAccessS2CPacket implements CustomPacketPayload {
    public static final Type<ScreenAccessS2CPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "screen_access_s2c"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenAccessS2CPacket> CODEC = StreamCodec.ofMember(ScreenAccessS2CPacket::write, ScreenAccessS2CPacket::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public final BlockPos pos;
    public final String json;
    public ScreenAccessS2CPacket(BlockPos pos, String json) {
        this.pos = pos;
        this.json = json;
    }
    public ScreenAccessS2CPacket(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        json = buf.readUtf(8192);
    }
    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(json, 8192);
    }
    public static void handle(ScreenAccessS2CPacket packet, NetworkManager.PacketContext context) { context.queue(() -> { ClientPacketHandler.handleScreenAccess(packet.pos, packet.json); }); }
}
