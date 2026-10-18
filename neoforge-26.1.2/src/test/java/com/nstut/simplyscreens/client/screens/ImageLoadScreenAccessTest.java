package com.nstut.simplyscreens.client.screens;

import com.nstut.openui.api.UIComponent;
import com.nstut.openui.minecraft.UiScreen;
import com.nstut.openui.runtime.NativeWidgetHost;
import com.nstut.openui.runtime.UiRuntime;
import com.nstut.openui.state.Signal;
import com.nstut.openui.state.Signals;
import com.nstut.openui.state.Subscription;
import com.nstut.simplyscreens.ScreenAccessSnapshot;
import com.nstut.simplyscreens.ScreenNetworkService;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.ScreenAccessC2SPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;

class ImageLoadScreenAccessTest {
    @TempDir static Path tempDir;
    private MockedStatic<Minecraft> clientInstance;

    @BeforeEach void provideHeadlessClient() {
        // In 26.1.2, Screen reads the client font during construction, before init().
        Minecraft client = mock(Minecraft.class);
        clientInstance = mockStatic(Minecraft.class);
        clientInstance.when(Minecraft::getInstance).thenReturn(client);
    }

    @AfterEach void releaseHeadlessClient() {
        if (clientInstance != null) clientInstance.close();
    }

    @BeforeAll static void isolatePreferences() {
        System.setProperty("simplyscreens.ui.config.dir", tempDir.toString());
    }

    @AfterAll static void restorePreferences() {
        System.clearProperty("simplyscreens.ui.config.dir");
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEW", "CONTROL"})
    void refreshUsesLatestRevisionForMenuSelection(String action) {
        ImageLoadScreen screen = new ImageLoadScreen(BlockPos.ZERO);
        screen.updateAccess(BlockPos.ZERO, snapshot(7));
        screen.updateAccess(BlockPos.ZERO, snapshot(8));
        try (var packets = mockStatic(PacketRegistries.class)) {
            screen.selectPolicy(action, "OWNER_ONLY");
            var sent = ArgumentCaptor.forClass(ScreenAccessC2SPacket.class);
            packets.verify(() -> PacketRegistries.sendToServer(sent.capture()));
            packets.verifyNoMoreInteractions();
            assertEquals(8, sent.getValue().revision);
            assertEquals("OWNER_ONLY", sent.getValue().value);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEW", "CONTROL"})
    void nonManagerCannotSubmitMenuSelection(String action) {
        ImageLoadScreen screen = new ImageLoadScreen(BlockPos.ZERO);
        var snapshot = ScreenAccessSnapshot.fromJson(snapshot(7));
        screen.updateAccess(BlockPos.ZERO, new ScreenAccessSnapshot(snapshot.key(), snapshot.state(), false, true, false).toJson());
        try (var packets = mockStatic(PacketRegistries.class)) {
            screen.selectPolicy(action, "PUBLIC");
            packets.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEW", "CONTROL"})
    void menuSelectionSendsOneChosenPolicyWithCurrentRevision(String action) {
        ImageLoadScreen screen = new ImageLoadScreen(BlockPos.ZERO);
        screen.updateAccess(BlockPos.ZERO, snapshot(9));
        try (var packets = mockStatic(PacketRegistries.class)) {
            screen.selectPolicy(action, "TRUSTED");
            var sent = ArgumentCaptor.forClass(ScreenAccessC2SPacket.class);
            packets.verify(() -> PacketRegistries.sendToServer(sent.capture()));
            packets.verifyNoMoreInteractions();
            assertEquals("TRUSTED", sent.getValue().value);
            assertEquals(action, sent.getValue().action);
            assertEquals(9, sent.getValue().revision);
        }
    }

    @Test
    void rebuildingAccessControlsDoesNotRetainSnapshotSubscriptions() throws ReflectiveOperationException {
        ImageLoadScreen screen = new ImageLoadScreen(BlockPos.ZERO);
        CountingAccessSignal source = trackAccessSnapshots(screen);
        screen.updateAccess(BlockPos.ZERO, snapshot(1));
        var build = ImageLoadScreen.class.getDeclaredMethod("buildAccess");
        build.setAccessible(true);
        for (int i = 0; i < 10; i++) {
            ((UIComponent) build.invoke(screen)).dispose();
            int reads = source.reads;
            source.set(ScreenAccessSnapshot.fromJson(snapshot(i + 2)));
            assertEquals(reads, source.reads, "Disposed Settings controls must not recompute on later snapshots");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingTrustedDialogsDoesNotRetainSnapshotSubscriptions(boolean hasMembers) throws ReflectiveOperationException {
        ImageLoadScreen screen = new ImageLoadScreen(BlockPos.ZERO);
        CountingAccessSignal source = trackAccessSnapshots(screen);
        var state = new ScreenNetworkService.State(UUID.randomUUID(), hasMembers ? Set.of(UUID.randomUUID()) : Set.of(),
                ScreenNetworkService.Policy.TRUSTED, ScreenNetworkService.Policy.OWNER_ONLY, null, "STATIC", 0, 0, 1);
        screen.updateAccess(BlockPos.ZERO, new ScreenAccessSnapshot("network/private", state, true, true, false).toJson());
        var runtimeField = UiScreen.class.getDeclaredField("uiRuntime");
        runtimeField.setAccessible(true);
        var open = ImageLoadScreen.class.getDeclaredMethod("manageTrustedPlayers");
        open.setAccessible(true);
        try (var runtime = new UiRuntime(mock(Font.class), mock(NativeWidgetHost.class))) {
            runtimeField.set(screen, runtime);
            for (int i = 0; i < 10; i++) {
                open.invoke(screen);
                assertEquals(1, runtime.overlays().size());
                runtime.overlays().closeTop();
                assertEquals(0, runtime.overlays().size());
                int reads = source.reads;
                source.set(ScreenAccessSnapshot.fromJson(snapshot(i + 2)));
                assertEquals(reads, source.reads, "Closed trusted dialogs must not recompute on later snapshots");
            }
        }
    }

    @Test
    void enteringSettingsRefreshesOnceAndRebindingDoesNotDuplicateRequests() {
        ImageLoadScreen screen = new ImageLoadScreen(BlockPos.ZERO);
        screen.updateAccess(BlockPos.ZERO, snapshot(7));
        screen.bindSettingsRefresh();
        screen.bindSettingsRefresh();
        try (var packets = mockStatic(PacketRegistries.class)) {
            screen.smokeSelectTab(2); // Import does not request access.
            packets.verifyNoInteractions();
            screen.smokeSelectTab(1);
            var sent = ArgumentCaptor.forClass(ScreenAccessC2SPacket.class);
            packets.verify(() -> PacketRegistries.sendToServer(sent.capture()));
            packets.verifyNoMoreInteractions();
            assertEquals("GET", sent.getValue().action);
            assertEquals(BlockPos.ZERO, sent.getValue().pos);
            assertEquals(7, sent.getValue().revision);
            screen.removed();
            screen.smokeSelectTab(0);
            screen.smokeSelectTab(1);
            packets.verifyNoMoreInteractions();
        }
    }

    private static CountingAccessSignal trackAccessSnapshots(ImageLoadScreen screen) throws ReflectiveOperationException {
        var field = ImageLoadScreen.class.getDeclaredField("accessView");
        field.setAccessible(true);
        CountingAccessSignal source = new CountingAccessSignal();
        field.set(screen, source);
        return source;
    }

    private static final class CountingAccessSignal implements Signal<ScreenAccessSnapshot> {
        private final Signal<ScreenAccessSnapshot> delegate = Signals.of(null);
        private int reads;
        @Override public ScreenAccessSnapshot get() { reads++; return delegate.get(); }
        @Override public void set(ScreenAccessSnapshot value) { delegate.set(value); }
        @Override public Subscription subscribe(Consumer<? super ScreenAccessSnapshot> listener) {
            return delegate.subscribe(listener);
        }
    }

    private static String snapshot(long revision) {
        var state = new ScreenNetworkService.State(UUID.randomUUID(), Set.of(),
                ScreenNetworkService.Policy.OWNER_ONLY, ScreenNetworkService.Policy.OWNER_ONLY,
                null, "STATIC", 0, 0, revision);
        return new ScreenAccessSnapshot("network/private", state, true, true, false).toJson();
    }
}
