package com.nstut.simplyscreens.helpers;

import com.nstut.simplyscreens.ScreenAccessSnapshot;
import com.nstut.simplyscreens.ScreenNetworkService;
import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.ScreenAccessC2SPacket;
import com.nstut.simplyscreens.network.ScreenAccessS2CPacket;
import com.nstut.simplyscreens.network.UpdateScreenS2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScreenAccessLinkTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    final UUID owner = UUID.randomUUID(), visitor = UUID.randomUUID(), display = UUID.randomUUID();
    final UUID sourceImage = UUID.randomUUID(), targetImage = UUID.randomUUID();
    final BlockPos pos = new BlockPos(1, 64, 1);
    final String sourceKey = ScreenNetworkService.network("source");
    ServerPlayer player;
    ScreenBlockEntity screen;
    AtomicReference<String> id;
    AtomicReference<UUID> image;


    @BeforeEach void setup(@TempDir Path directory) {
        ScreenRegistry.init(directory);
        var server = mock(MinecraftServer.class);
        var level = mock(ServerLevel.class);
        var players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(level.getServer()).thenReturn(server);
        player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(owner);
        screen = mock(ScreenBlockEntity.class);
        id = new AtomicReference<>("source");
        image = new AtomicReference<>(sourceImage);
        when(screen.getDisplayId()).thenReturn(display);
        when(screen.getBlockPos()).thenReturn(pos);
        when(screen.getScreenId()).thenAnswer(call -> id.get());
        when(screen.accessKey()).thenAnswer(call -> id.get().isEmpty()
                ? ScreenNetworkService.display(display) : ScreenNetworkService.network(id.get()));
        when(screen.getImageId()).thenAnswer(call -> image.get());
        when(screen.getResolvedImageId()).thenAnswer(call -> image.get());
        when(screen.isMaintainAspectRatio()).thenReturn(true);
        when(screen.getScreenWidth()).thenReturn(2);
        when(screen.getScreenHeight()).thenReturn(3);
        doAnswer(call -> { id.set(call.getArgument(0)); return null; }).when(screen).setScreenId(anyString());
        doAnswer(call -> { image.set(call.getArgument(0)); return null; }).when(screen).setImageId(any());
        assertTrue(ScreenRegistry.networks().createOwned(sourceKey, owner, sourceImage));
    }

    @Test void foreignDestinationIsRejectedAndAuthoritativeDisplayIsReturned() {
        ScreenRegistry.networks().createOwned(ScreenNetworkService.network("foreign"), visitor, targetImage);
        var reply = submit(sourceKey, 0, "foreign", 11);
        assertFalse(reply.success());
        assertEquals(sourceKey, reply.key());
        assertEquals("source", id.get());
        verify(screen, never()).setScreenId(anyString());
    }

    @Test void readOnlyCallerCannotRelinkAndReceivesFailure() {
        when(player.getUUID()).thenReturn(visitor);
        var reply = submit(sourceKey, 0, "other", 12);
        assertFalse(reply.success());
        assertFalse(reply.manage());
        assertEquals("source", id.get());
        assertNull(ScreenRegistry.networks().snapshot(ScreenNetworkService.network("other")));
    }

    @Test void staleRevisionAndSourceKeyCannotRelink() {
        assertFalse(submit(sourceKey, 99, "other", 13).success());
        assertFalse(submit(ScreenNetworkService.network("old"), 0, "other", 14).success());
        verify(screen, never()).setScreenId(anyString());
    }

    @Test void ownedTargetLinkAndDetachReturnCurrentIdentityAndContent() {
        String destination = ScreenNetworkService.network("target");
        ScreenRegistry.networks().createOwned(destination, owner, targetImage);
        var linked = submit(sourceKey, 0, "target", 15);
        assertTrue(linked.success());
        assertEquals(destination, linked.key());
        assertEquals(targetImage, linked.state().image());
        assertEquals(targetImage, image.get());
        var detached = submit(destination, linked.state().revision(), "", 16);
        assertTrue(detached.success());
        assertEquals(ScreenNetworkService.display(display), detached.key());
        assertEquals("", id.get());
        assertEquals(targetImage, image.get());
    }

    @Test void invalidLinkDoesNotDetachTheDisplay() {
        assertFalse(submit(sourceKey, 0, "bad id!", 17).success());
        assertEquals("source", id.get());
    }

    @Test void linkPreservesAndReportsLocalImageOverride() {
        String destination = ScreenNetworkService.network("target");
        ScreenRegistry.networks().createOwned(destination, owner, targetImage);
        when(screen.isLocalImageOverride()).thenReturn(true);
        var reply = submit(sourceKey, 0, "target", 18);
        assertTrue(reply.success());
        assertTrue(reply.localOverride());
        assertEquals(sourceImage, image.get());
        verify(screen, never()).setImageId(any());
    }

    @Test void missingNearbyDisplayDoesNotFabricateAnAcknowledgement() {
        try (var nearby = mockStatic(ScreenPacketSecurity.class); var packets = mockStatic(PacketRegistries.class)) {
            ScreenAccessService.access(player, new ScreenAccessC2SPacket(pos, sourceKey, 0, "LINK", "target", 19));
            packets.verifyNoInteractions();
            verify(screen, never()).setScreenId(anyString());
        }
    }

    private ScreenAccessSnapshot submit(String key, long revision, String value, long requestId) {
        try (var nearby = mockStatic(ScreenPacketSecurity.class); var packets = mockStatic(PacketRegistries.class)) {
            nearby.when(() -> ScreenPacketSecurity.nearby(player, pos)).thenReturn(screen);
            ScreenAccessService.access(player, new ScreenAccessC2SPacket(pos, key, revision, "LINK", value, requestId));
            var response = ArgumentCaptor.forClass(ScreenAccessS2CPacket.class);
            packets.verify(() -> PacketRegistries.sendToPlayer(eq(player), response.capture()));
            var update = ArgumentCaptor.forClass(UpdateScreenS2CPacket.class);
            packets.verify(() -> PacketRegistries.sendToPlayer(eq(player), update.capture()));
            packets.verifyNoMoreInteractions();
            assertEquals(id.get(), update.getValue().getScreenId());
            assertEquals(image.get(), update.getValue().getImageId());
            assertEquals(pos, update.getValue().getPos());
            assertEquals(pos, update.getValue().getAnchorPos());
            assertTrue(update.getValue().isMaintainAspectRatio());
            assertEquals(2, update.getValue().getScreenWidth());
            assertEquals(3, update.getValue().getScreenHeight());
            var snapshot = ScreenAccessSnapshot.fromJson(response.getValue().json);
            assertEquals(requestId, snapshot.requestId());
            return snapshot;
        }
    }
}
