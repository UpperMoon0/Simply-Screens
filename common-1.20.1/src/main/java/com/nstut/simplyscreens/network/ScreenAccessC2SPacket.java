package com.nstut.simplyscreens.network;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import java.util.function.Supplier;
public final class ScreenAccessC2SPacket {
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
    public ScreenAccessC2SPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        key = buf.readUtf(128);
        revision = buf.readLong();
        action = buf.readUtf(128);
        value = buf.readUtf(128);
        requestId = buf.readLong();
    }
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(key, 128);
        buf.writeLong(revision);
        buf.writeUtf(action, 128);
        buf.writeUtf(value, 128);
        buf.writeLong(requestId);
    }
    public void handle(Supplier<NetworkManager.PacketContext> context) { context.get().queue(() -> { com.nstut.simplyscreens.helpers.ScreenAccessService.access((net.minecraft.server.level.ServerPlayer) context.get().getPlayer(), this); }); }
}
