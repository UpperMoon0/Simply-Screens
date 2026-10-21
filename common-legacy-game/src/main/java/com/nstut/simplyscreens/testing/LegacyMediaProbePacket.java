package com.nstut.simplyscreens.testing;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** Fixed-size test fixture control only. All image transfers use production packets. */
public final class LegacyMediaProbePacket {
    public static final int REQUEST = 0, FIXTURE = 1, VERIFIED = 2, CLEANUP = 3, CLEANED = 4, FAILED = 5;
    public static final ResourceLocation C2S = new ResourceLocation("simply_screens", "legacy_media_probe_c2s");
    public static final ResourceLocation S2C = new ResourceLocation("simply_screens", "legacy_media_probe_s2c");
    public final int phase;
    public final UUID session;
    public final BlockPos pos;
    public final UUID display, image;
    public LegacyMediaProbePacket(int phase, UUID session, BlockPos pos, UUID display, UUID image) {
        if (phase < REQUEST || phase > FAILED || session == null) throw new IllegalArgumentException("Invalid probe phase/session");
        this.phase = phase; this.session = session; this.pos = pos; this.display = display; this.image = image;
    }
    public boolean serverbound() { return phase == REQUEST || phase == CLEANUP; }
    public ResourceLocation channel() {
        if (!LegacyMediaProbeProtocol.ENABLED) throw new IllegalStateException("Media probe is disabled");
        return serverbound() ? C2S : S2C;
    }
    public void write(FriendlyByteBuf buf) {
        channel();
        buf.writeByte(phase); buf.writeUUID(session);
        buf.writeBoolean(pos != null); if (pos != null) buf.writeBlockPos(pos);
        buf.writeBoolean(display != null); if (display != null) buf.writeUUID(display);
        buf.writeBoolean(image != null); if (image != null) buf.writeUUID(image);
    }
    public static LegacyMediaProbePacket read(FriendlyByteBuf buf, boolean serverbound) {
        if (!LegacyMediaProbeProtocol.ENABLED) throw new IllegalStateException("Media probe is disabled");
        int phase = buf.readUnsignedByte(); UUID session = buf.readUUID();
        BlockPos pos = buf.readBoolean() ? buf.readBlockPos() : null;
        UUID display = buf.readBoolean() ? buf.readUUID() : null;
        UUID image = buf.readBoolean() ? buf.readUUID() : null;
        LegacyMediaProbePacket packet = new LegacyMediaProbePacket(phase, session, pos, display, image);
        if (packet.serverbound() != serverbound || buf.isReadable()) throw new IllegalArgumentException("Invalid probe direction/trailing bytes");
        return packet;
    }
}
