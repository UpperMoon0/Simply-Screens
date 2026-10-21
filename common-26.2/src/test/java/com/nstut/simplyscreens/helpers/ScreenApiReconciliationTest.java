package com.nstut.simplyscreens.helpers;

import com.nstut.simplyscreens.*;
import com.nstut.simplyscreens.api.ScreenControlService;
import com.nstut.simplyscreens.playlist.*;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScreenApiReconciliationTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @TempDir Path root;
    final UUID owner = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
    final String key = ScreenNetworkService.network("api-retry");
    ScreenPlaylistService store;
    ScreenControlService control;
    final ScreenNetworkService.Actor actor = new ScreenNetworkService.Actor(owner, false);
    @BeforeEach void setup() {
        ScreenRegistry.init(root);
        ScreenRegistry.networks().createOwned(key, owner, first);
        store = ScreenPlaylistRuntime.service();
        control = new ScreenControlService(ScreenRegistry.networks(), store);
    }
    @AfterEach void cleanup() { ScreenPlaylistRuntime.shutdown(); }
    UUID playlist() {
        UUID id = store.create(owner, "Retry", store.library(owner).revision());
        assertTrue(store.replaceSlides(owner, id, List.of(new PlaylistSlide(first,1000),new PlaylistSlide(second,1000)),store.library(owner).revision()));
        return id;
    }
    @Test void cancelledSessionCannotApplyItsUndrainedTransitionOverStaticContent() throws Exception {
        UUID id = playlist();
        for (int n=0;n<40;n++) {
            String target = ScreenNetworkService.network("pending-"+n);
            assertTrue(ScreenRegistry.networks().createOwned(target, owner, first));
            assertTrue(store.play(target, owner, id, 0));
        }
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getTickCount()).thenReturn(0,20,21);
        try (var access = mockStatic(ScreenAccessService.class)) {
            ScreenPlaylistRuntime.tick(server);
            ScreenPlaylistRuntime.tick(server);
            var field = ScreenPlaylistRuntime.class.getDeclaredField("PENDING_CONTENT");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") Set<String> pending = (Set<String>)field.get(null);
            assertEquals(8,pending.size(),"The first tick must leave transitions beyond the 32-key budget");
            String target = pending.iterator().next();
            UUID selected = UUID.randomUUID();
            assertTrue(control.select(actor,target,selected, image -> true));
            ScreenPlaylistRuntime.tick(server);
            assertEquals(selected,ScreenRegistry.networks().snapshot(target).image());
            assertFalse(store.managesContent(target));
        }
    }
    @Test void failedPausedNextAndStopReconcileOnTheNextTick() {
        assertTrue(control.play(actor,key,owner,playlist(), image -> true));
        ScreenPlaylistRuntime.reconcile(key);
        assertTrue(control.transport(actor,key,"PAUSE"));
        MinecraftServer server = mock(MinecraftServer.class);
        for (String action : List.of("NEXT","STOP")) {
            assertTrue(control.transport(actor,key,action));
            UUID expected = store.snapshot(key).currentImage();
            UUID before = ScreenRegistry.networks().snapshot(key).image();
            try (var registry = mockStatic(ScreenRegistry.class, CALLS_REAL_METHODS)) {
                registry.when(() -> ScreenRegistry.reconcileContent(key,expected)).thenThrow(new IllegalStateException("Injected write failure"));
                ScreenRegistry.reconcileContent(key,before);
                assertThrows(IllegalStateException.class, () -> ScreenPlaylistRuntime.reconcile(key));
                assertEquals(before,ScreenRegistry.networks().snapshot(key).image());
            }
            try (var access = mockStatic(ScreenAccessService.class)) { ScreenPlaylistRuntime.tick(server); }
            assertEquals(expected,ScreenRegistry.networks().snapshot(key).image(), action);
        }
    }
}
