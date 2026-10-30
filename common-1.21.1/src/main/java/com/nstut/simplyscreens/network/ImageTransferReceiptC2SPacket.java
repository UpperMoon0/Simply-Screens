package com.nstut.simplyscreens.network;
import dev.architectury.networking.NetworkManager;
import com.nstut.packetweave.api.OutboundTransfers;
import java.util.UUID;
import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public final class ImageTransferReceiptC2SPacket implements CustomPacketPayload {
    public static final Type<ImageTransferReceiptC2SPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimplyScreens.MOD_ID, "image_receipt_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ImageTransferReceiptC2SPacket> CODEC = StreamCodec.ofMember(ImageTransferReceiptC2SPacket::write, ImageTransferReceiptC2SPacket::new);
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
    private final UUID id;
    private final long bytes;
    public ImageTransferReceiptC2SPacket(UUID id, long bytes) { this.id=id; this.bytes=bytes; }
    public ImageTransferReceiptC2SPacket(RegistryFriendlyByteBuf buf) { id=buf.readUUID(); bytes=buf.readLong(); }
    public void write(RegistryFriendlyByteBuf buf) { buf.writeUUID(id); buf.writeLong(bytes); }
    public static void handle(ImageTransferReceiptC2SPacket packet, NetworkManager.PacketContext context) {
        context.queue(() -> OutboundTransfers.shared().acknowledge(context.getPlayer(), packet.id, packet.bytes));
    }
}
