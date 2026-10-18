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
    void changingPrivatePolicyToTrustedSendsOnlyTheAppliedPolicy(String action) {
        ImageLoadScreen screen = new ImageLoadScreen(BlockPos.ZERO);
        screen.updateAccess(BlockPos.ZERO, snapshot(7));
        try (var packets = mockStatic(PacketRegistries.class)) {
            // OWNER_ONLY -> OPERATORS -> PUBLIC -> TRUSTED must remain a local preview.
            screen.cyclePolicy(action);
            screen.cyclePolicy(action);
            screen.cyclePolicy(action);
            packets.verifyNoInteractions();

            screen.applyPolicy(action);
            var sent = ArgumentCaptor.forClass(ScreenAccessC2SPacket.class);
            packets.verify(() -> PacketRegistries.sendToServer(sent.capture()));
            packets.verifyNoMoreInteractions();
            assertEquals(BlockPos.ZERO, sent.getValue().pos);
            assertEquals("network/private", sent.getValue().key);
            assertEquals(7, sent.getValue().revision);
            assertEquals(action, sent.getValue().action);
            assertEquals("TRUSTED", sent.getValue().value);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEW", "CONTROL"})
    void serverRefreshDiscardsUnappliedPolicyPreview(String action) {
        ImageLoadScreen screen = new ImageLoadScreen(BlockPos.ZERO);
        screen.updateAccess(BlockPos.ZERO, snapshot(7));
        try (var packets = mockStatic(PacketRegistries.class)) {
            screen.cyclePolicy(action);
            screen.cyclePolicy(action);
            packets.verifyNoInteractions();
            screen.updateAccess(BlockPos.ZERO, snapshot(8));
            screen.applyPolicy(action);

            var sent = ArgumentCaptor.forClass(ScreenAccessC2SPacket.class);
            packets.verify(() -> PacketRegistries.sendToServer(sent.capture()));
            packets.verifyNoMoreInteractions();
            assertEquals(8, sent.getValue().revision);
            assertEquals("OWNER_ONLY", sent.getValue().value);
        }
    }

    private static String snapshot(long revision) {
        var state = new ScreenNetworkService.State(UUID.randomUUID(), Set.of(),
                ScreenNetworkService.Policy.OWNER_ONLY, ScreenNetworkService.Policy.OWNER_ONLY,
                null, "STATIC", 0, 0, revision);
        return new ScreenAccessSnapshot("network/private", state, true, true, false).toJson();
    }
}
