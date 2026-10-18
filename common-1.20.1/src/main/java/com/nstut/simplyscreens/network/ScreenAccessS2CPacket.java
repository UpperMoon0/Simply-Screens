package com.nstut.simplyscreens.network;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import dev.architectury.networking.NetworkManager;
import java.util.function.Supplier;
public final class ScreenAccessS2CPacket {
    public final BlockPos pos;
    public final String json;
    public ScreenAccessS2CPacket(BlockPos pos, String json) {
        this.pos = pos;
        this.json = json;
    }
    public ScreenAccessS2CPacket(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        json = buf.readUtf(8192);
    }
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(json, 8192);
    }
    public void handle(Supplier<NetworkManager.PacketContext> context) { context.get().queue(() -> { ClientPacketHandler.handleScreenAccess(this.pos, this.json); }); }
}
