package com.nstut.simplyscreens.client.screens;

import com.nstut.simplyscreens.ScreenAccessSnapshot;
import com.nstut.simplyscreens.ScreenNetworkService;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.ScreenAccessC2SPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

class ImageLoadScreenAccessTest {
    @TempDir static Path tempDir;

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

    private static String snapshot(long revision) {
        var state = new ScreenNetworkService.State(UUID.randomUUID(), Set.of(),
                ScreenNetworkService.Policy.OWNER_ONLY, ScreenNetworkService.Policy.OWNER_ONLY,
                null, "STATIC", 0, 0, revision);
        return new ScreenAccessSnapshot("network/private", state, true, true, false).toJson();
    }
}
