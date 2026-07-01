package com.nstut.simplyscreens.network;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class PacketSerializationTest {

    private BlockPos pos;

    @BeforeEach
    void setUp() {
        pos = new BlockPos(10, 20, 30);
    }

    @Test
    void updateScreenIdC2SPacket_roundTrip() {
        String screenId = "test-screen-id";
        UpdateScreenIdC2SPacket original = new UpdateScreenIdC2SPacket(pos, screenId);

        ByteBuf raw = Unpooled.buffer();
        FriendlyByteBuf buf = new FriendlyByteBuf(raw);
        original.write(buf);

        UpdateScreenIdC2SPacket decoded = new UpdateScreenIdC2SPacket(buf);

        assertEquals(pos, decoded.pos);
        assertEquals(screenId, decoded.screenId);
    }

    @Test
    void updateScreenIdC2SPacket_emptyScreenIdRoundTrip() {
        UpdateScreenIdC2SPacket original = new UpdateScreenIdC2SPacket(pos, "");

        ByteBuf raw = Unpooled.buffer();
        FriendlyByteBuf buf = new FriendlyByteBuf(raw);
        original.write(buf);

        UpdateScreenIdC2SPacket decoded = new UpdateScreenIdC2SPacket(buf);

        assertEquals(pos, decoded.pos);
    }

    @Test
    void updateScreenIdC2SPacket_nullScreenId() {
        UpdateScreenIdC2SPacket original = new UpdateScreenIdC2SPacket(pos, null);

        ByteBuf raw = Unpooled.buffer();
        FriendlyByteBuf buf = new FriendlyByteBuf(raw);
        original.write(buf);

        UpdateScreenIdC2SPacket decoded = new UpdateScreenIdC2SPacket(buf);

        assertEquals(pos, decoded.pos);
    }

    @Test
    void updateScreenS2CPacket_roundTrip_withImageId() {
        UUID imageId = UUID.randomUUID();
        String screenId = "screen-123";

        UpdateScreenS2CPacket original = new UpdateScreenS2CPacket(pos, imageId, true, screenId);

        ByteBuf raw = Unpooled.buffer();
        FriendlyByteBuf buf = new FriendlyByteBuf(raw);
        original.write(buf);

        UpdateScreenS2CPacket decoded = new UpdateScreenS2CPacket(buf);

        assertEquals(pos, decoded.pos);
        assertEquals(imageId, decoded.imageId);
        assertTrue(decoded.maintainAspectRatio);
        assertEquals(screenId, decoded.screenId);
    }

    @Test
    void updateScreenS2CPacket_roundTrip_nullImageId() {
        UpdateScreenS2CPacket original = new UpdateScreenS2CPacket(pos, null, false, "screen-null-img");

        ByteBuf raw = Unpooled.buffer();
        FriendlyByteBuf buf = new FriendlyByteBuf(raw);
        original.write(buf);

        UpdateScreenS2CPacket decoded = new UpdateScreenS2CPacket(buf);

        assertEquals(pos, decoded.pos);
        assertNull(decoded.imageId);
        assertFalse(decoded.maintainAspectRatio);
        assertEquals("screen-null-img", decoded.screenId);
    }
}
