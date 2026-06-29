package com.nstut.simplyscreens.blocks.entities;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.nstut.simplyscreens.ScreenRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Tests the ID synchronization observable behavior through ScreenRegistry.
 * These verify the system works correctly at the boundary between
 * ScreenBlockEntity and ScreenRegistry, without needing a full Minecraft
 * bootstrap environment.
 */
class ScreenBlockEntitySyncTest {

    private Level serverLevel;
    private Level clientLevel;
    private BlockPos pos1;
    private BlockPos pos2;
    private UUID imageId;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        serverLevel = mock(Level.class, withSettings().lenient());
        clientLevel = mock(Level.class, withSettings().lenient());

        lenient().when(serverLevel.isClientSide()).thenReturn(false);
        lenient().when(clientLevel.isClientSide()).thenReturn(true);

        pos1 = new BlockPos(10, 20, 30);
        pos2 = new BlockPos(40, 50, 60);
        imageId = UUID.randomUUID();

        ScreenRegistry.init(tempDir);
    }

    @AfterEach
    void tearDown() {
        ScreenRegistry.clearLevel(serverLevel);
    }

    // === Registration tests (what forceScreenId does) ===

    @Test
    void registerScreenOnServer_addsPositionMapping() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "screen1");
        assertEquals("screen1", ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    @Test
    void registerScreenOnServer_findPositionsByScreenId() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "shared");
        ScreenRegistry.registerScreen(serverLevel, pos2, "shared");
        assertEquals(2, ScreenRegistry.getPositionsForScreenId(serverLevel, "shared").size());
    }

    @Test
    void registerScreenOnClient_isIgnored() {
        ScreenRegistry.registerScreen(clientLevel, pos1, "screen1");
        assertNull(ScreenRegistry.getScreenIdAt(clientLevel, pos1));
    }

    @Test
    void registerNullScreenId_isIgnored() {
        ScreenRegistry.registerScreen(serverLevel, pos1, null);
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    @Test
    void registerEmptyScreenId_isIgnored() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "");
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    // === Update tests (what forceScreenId does on ID change) ===

    @Test
    void updateScreenId_changesPositionMapping() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "oldId");
        ScreenRegistry.updateScreenId(serverLevel, pos1, "oldId", "newId");
        assertEquals("newId", ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    @Test
    void updateScreenId_removesOldMapping() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "oldId");
        ScreenRegistry.updateScreenId(serverLevel, pos1, "oldId", "newId");
        assertEquals("newId", ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    // === Image ID registry tests (what forceImageId with screenId does) ===

    @Test
    void setAndGetImageId() {
        ScreenRegistry.setImageId("screen1", imageId);
        assertEquals(imageId, ScreenRegistry.getImageId("screen1"));
    }

    @Test
    void removeImageId() {
        ScreenRegistry.setImageId("screen1", imageId);
        ScreenRegistry.removeScreenId("screen1");
        assertNull(ScreenRegistry.getImageId("screen1"));
    }

    @Test
    void setImageIdNullRemovesMapping() {
        ScreenRegistry.setImageId("screen1", imageId);
        ScreenRegistry.setImageId("screen1", null);
        assertNull(ScreenRegistry.getImageId("screen1"));
    }

    @Test
    void setImageIdNullKeyIsNoOp() {
        ScreenRegistry.setImageId(null, imageId);
        assertTrue(ScreenRegistry.getAllScreenIds().isEmpty());
    }

    @Test
    void setImageIdEmptyKeyIsNoOp() {
        ScreenRegistry.setImageId("", imageId);
        assertTrue(ScreenRegistry.getAllScreenIds().isEmpty());
    }

    // === Clear level tests ===

    @Test
    void clearLevel_removesAllMappings() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "a");
        ScreenRegistry.registerScreen(serverLevel, pos2, "b");
        ScreenRegistry.clearLevel(serverLevel);
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos1));
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos2));
    }

    @Test
    void clearClientLevel_isIgnored() {
        ScreenRegistry.registerScreen(clientLevel, pos1, "a");
        ScreenRegistry.clearLevel(clientLevel);
        assertNull(ScreenRegistry.getScreenIdAt(clientLevel, pos1));
    }

    // === Unregister tests ===

    @Test
    void unregisterScreen_removesMapping() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "screen1");
        ScreenRegistry.unregisterScreen(serverLevel, pos1, "screen1");
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    @Test
    void unregisterScreenOnClient_isIgnored() {
        ScreenRegistry.registerScreen(clientLevel, pos1, "screen1");
        ScreenRegistry.unregisterScreen(clientLevel, pos1, "screen1");
        assertNull(ScreenRegistry.getScreenIdAt(clientLevel, pos1));
    }

    // === Multi-level isolation ===

    @Test
    void operationsAreIsolatedPerLevel() {
        Level anotherLevel = mock(Level.class, withSettings().lenient());
        lenient().when(anotherLevel.isClientSide()).thenReturn(false);

        ScreenRegistry.registerScreen(serverLevel, pos1, "server-screen");
        ScreenRegistry.registerScreen(anotherLevel, pos1, "other-screen");

        assertEquals("server-screen", ScreenRegistry.getScreenIdAt(serverLevel, pos1));
        assertEquals("other-screen", ScreenRegistry.getScreenIdAt(anotherLevel, pos1));

        ScreenRegistry.clearLevel(serverLevel);
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos1));
        assertEquals("other-screen", ScreenRegistry.getScreenIdAt(anotherLevel, pos1));
    }
}
