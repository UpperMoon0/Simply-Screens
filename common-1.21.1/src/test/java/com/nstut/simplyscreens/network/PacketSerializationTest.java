package com.nstut.simplyscreens.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.quality.Strictness;

import java.util.UUID;

class PacketSerializationTest {

    private RegistryAccess registryAccess;
    private BlockPos pos;

    @BeforeEach
    void setUp() {
        registryAccess = mock(RegistryAccess.class, withSettings().lenient());
        pos = new BlockPos(10, 20, 30);
    }

    @Test
    void updateScreenIdC2SPacket_roundTrip() {
        String screenId = "test-screen-id";
        UpdateScreenIdC2SPacket original = new UpdateScreenIdC2SPacket(pos, screenId);

        ByteBuf raw = Unpooled.buffer();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, registryAccess);
        original.write(buf);

        UpdateScreenIdC2SPacket decoded = new UpdateScreenIdC2SPacket(buf);

        assertEquals(pos, decoded.getPos());
        assertEquals(screenId, decoded.getScreenId());
    }

    @Test
    void updateScreenS2CPacket_roundTrip_withImageId() {
        UUID imageId = UUID.randomUUID();
        String screenId = "screen-123";

        UpdateScreenS2CPacket original = new UpdateScreenS2CPacket(pos, imageId, true, screenId, 3, 2);

        ByteBuf raw = Unpooled.buffer();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, registryAccess);
        original.write(buf);

        UpdateScreenS2CPacket decoded = new UpdateScreenS2CPacket(buf);

        assertEquals(pos, decoded.getPos());
        assertEquals(imageId, decoded.getImageId());
        assertTrue(decoded.isMaintainAspectRatio());
        assertEquals(screenId, decoded.getScreenId());
        assertEquals(3, decoded.getScreenWidth());
        assertEquals(2, decoded.getScreenHeight());
    }

    @Test
    void updateScreenS2CPacket_roundTrip_nullImageId() {
        String screenId = "screen-null-img";

        UpdateScreenS2CPacket original = new UpdateScreenS2CPacket(pos, null, false, screenId, 1, 1);

        ByteBuf raw = Unpooled.buffer();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, registryAccess);
        original.write(buf);

        UpdateScreenS2CPacket decoded = new UpdateScreenS2CPacket(buf);

        assertEquals(pos, decoded.getPos());
        assertNull(decoded.getImageId());
        assertFalse(decoded.isMaintainAspectRatio());
        assertEquals(screenId, decoded.getScreenId());
        assertEquals(1, decoded.getScreenWidth());
        assertEquals(1, decoded.getScreenHeight());
    }

    @Test
    void updateScreenS2CPacket_roundTrip_emptyScreenId() {
        UUID imageId = UUID.randomUUID();

        UpdateScreenS2CPacket original = new UpdateScreenS2CPacket(pos, imageId, true, "", 1, 1);

        ByteBuf raw = Unpooled.buffer();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, registryAccess);
        original.write(buf);

        UpdateScreenS2CPacket decoded = new UpdateScreenS2CPacket(buf);

        assertEquals(pos, decoded.getPos());
        assertEquals(imageId, decoded.getImageId());
        assertTrue(decoded.isMaintainAspectRatio());
        assertEquals("", decoded.getScreenId());
        assertEquals(1, decoded.getScreenWidth());
        assertEquals(1, decoded.getScreenHeight());
    }

    @Test
    void updateScreenIdC2SPacket_emptyScreenIdRoundTrip() {
        UpdateScreenIdC2SPacket original = new UpdateScreenIdC2SPacket(pos, "");

        ByteBuf raw = Unpooled.buffer();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, registryAccess);
        original.write(buf);

        UpdateScreenIdC2SPacket decoded = new UpdateScreenIdC2SPacket(buf);

        assertEquals(pos, decoded.getPos());
        assertNull(decoded.getScreenId());
    }

    @Test
    void updateScreenIdC2SPacket_roundTrip_nullScreenId() {
        UpdateScreenIdC2SPacket original = new UpdateScreenIdC2SPacket(pos, null);

        ByteBuf raw = Unpooled.buffer();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(raw, registryAccess);
        original.write(buf);

        UpdateScreenIdC2SPacket decoded = new UpdateScreenIdC2SPacket(buf);

        assertEquals(pos, decoded.getPos());
    }
}
