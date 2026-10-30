package com.nstut.simplyscreens.network;
import dev.architectury.networking.NetworkManager;
import com.nstut.packetweave.api.OutboundTransfers;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
public final class ImageTransferReceiptC2SPacket implements IPacket {
    private final UUID id;
    private final long bytes;
    public ImageTransferReceiptC2SPacket(UUID id, long bytes) { this.id=id; this.bytes=bytes; }
    public ImageTransferReceiptC2SPacket(FriendlyByteBuf buf) { id=buf.readUUID(); bytes=buf.readLong(); }
    public void write(FriendlyByteBuf buf) { buf.writeUUID(id); buf.writeLong(bytes); }
    public void handle(java.util.function.Supplier<NetworkManager.PacketContext> context) {
        context.get().queue(() -> OutboundTransfers.shared().acknowledge(context.get().getPlayer(), id, bytes));
    }
}
