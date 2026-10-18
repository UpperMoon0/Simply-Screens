package com.nstut.simplyscreens.network;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
public final class ScreenAccessC2SPacket implements CustomPacketPayload {
    public static final Type<ScreenAccessC2SPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SimplyScreens.MOD_ID, "screen_access_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenAccessC2SPacket> CODEC = StreamCodec.ofMember(ScreenAccessC2SPacket::write, ScreenAccessC2SPacket::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public final BlockPos pos;
    public final String key;
    public final long revision;
    public final String action;
    public final String value;
    public final long requestId;
    public ScreenAccessC2SPacket(BlockPos pos, String key, long revision, String action, String value) {
        this(pos, key, revision, action, value, 0);
    }
    public ScreenAccessC2SPacket(BlockPos pos, String key, long revision, String action, String value, long requestId) {
        this.pos = pos;
        this.key = key;
        this.revision = revision;
        this.action = action;
        this.value = value;
        this.requestId = requestId;
    }
    public ScreenAccessC2SPacket(RegistryFriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        key = buf.readUtf(128);
        revision = buf.readLong();
        action = buf.readUtf(128);
        value = buf.readUtf(128);
        requestId = buf.readLong();
    }
    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(key, 128);
        buf.writeLong(revision);
        buf.writeUtf(action, 128);
        buf.writeUtf(value, 128);
        buf.writeLong(requestId);
    }
    public static void handle(ScreenAccessC2SPacket packet, NetworkManager.PacketContext context) { context.queue(() -> { com.nstut.simplyscreens.helpers.ScreenAccessService.access((net.minecraft.server.level.ServerPlayer) context.getPlayer(), packet); }); }
}
