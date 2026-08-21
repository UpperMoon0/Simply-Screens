package com.nstut.simplyscreens;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

class ScreenRegistryTest {

    private Level serverLevel;
    private Level clientLevel;
    private Level anotherServer;
    private BlockPos pos1;
    private BlockPos pos2;
    private BlockPos pos3;
    private UUID imageId;
    private Path savedTempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        savedTempDir = tempDir;
        serverLevel = mock(Level.class, withSettings().lenient());
        clientLevel = mock(Level.class, withSettings().lenient());
        anotherServer = mock(Level.class, withSettings().lenient());

        lenient().when(serverLevel.isClientSide()).thenReturn(false);
        lenient().when(clientLevel.isClientSide()).thenReturn(true);
        lenient().when(anotherServer.isClientSide()).thenReturn(false);

        pos1 = new BlockPos(10, 20, 30);
        pos2 = new BlockPos(11, 20, 30);
        pos3 = new BlockPos(12, 20, 30);
        imageId = UUID.randomUUID();

        ScreenRegistry.init(tempDir);
    }

    @AfterEach
    void tearDown() {
        ScreenRegistry.clearLevel(serverLevel);
        ScreenRegistry.clearLevel(clientLevel);
        ScreenRegistry.clearLevel(anotherServer);
    }

    @Test
    void registerScreen_addsMappingOnServer() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "screen1");
        assertEquals("screen1", ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    @Test
    void registerScreen_ignoresClientLevel() {
        ScreenRegistry.registerScreen(clientLevel, pos1, "screen1");
        assertNull(ScreenRegistry.getScreenIdAt(clientLevel, pos1));
    }

    @Test
    void registerScreen_ignoresNullScreenId() {
        ScreenRegistry.registerScreen(serverLevel, pos1, null);
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    @Test
    void registerScreen_ignoresEmptyScreenId() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "");
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    @Test
    void unregisterScreen_removesMapping() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "screen1");
        ScreenRegistry.unregisterScreen(serverLevel, pos1, "screen1");
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    @Test
    void unregisterScreen_cleansUpEmptyLevel() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "screen1");
        ScreenRegistry.unregisterScreen(serverLevel, pos1, "screen1");
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    @Test
    void updateScreenId_changesId() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "oldId");
        ScreenRegistry.updateScreenId(serverLevel, pos1, "oldId", "newId");
        assertEquals("newId", ScreenRegistry.getScreenIdAt(serverLevel, pos1));
    }

    @Test
    void getPositionsForScreenId_returnsAllMatchingPositions() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "shared");
        ScreenRegistry.registerScreen(serverLevel, pos2, "shared");
        ScreenRegistry.registerScreen(serverLevel, pos3, "other");

        List<BlockPos> positions = ScreenRegistry.getPositionsForScreenId(serverLevel, "shared");
        assertEquals(2, positions.size());
        assertTrue(positions.contains(pos1));
        assertTrue(positions.contains(pos2));
    }

    @Test
    void getPositionsForScreenId_returnsEmptyForUnknown() {
        List<BlockPos> positions = ScreenRegistry.getPositionsForScreenId(serverLevel, "unknown");
        assertTrue(positions.isEmpty());
    }

    @Test
    void getPositionsForScreenId_ignoresClientLevel() {
        ScreenRegistry.registerScreen(clientLevel, pos1, "shared");
        assertTrue(ScreenRegistry.getPositionsForScreenId(clientLevel, "shared").isEmpty());
    }

    @Test
    void getPositionsForScreenId_ignoresNullScreenId() {
        assertTrue(ScreenRegistry.getPositionsForScreenId(serverLevel, null).isEmpty());
    }

    @Test
    void clearLevel_removesAllEntries() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "screen1");
        ScreenRegistry.registerScreen(serverLevel, pos2, "screen2");
        ScreenRegistry.clearLevel(serverLevel);
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos1));
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos2));
    }

    @Test
    void clearLevel_ignoresClientLevel() {
        ScreenRegistry.registerScreen(clientLevel, pos1, "screen1");
        ScreenRegistry.clearLevel(clientLevel);
        assertNull(ScreenRegistry.getScreenIdAt(clientLevel, pos1));
    }

    @Test
    void operationsAreIsolatedPerLevel() {
        ScreenRegistry.registerScreen(serverLevel, pos1, "level1-screen");
        ScreenRegistry.registerScreen(anotherServer, pos1, "level2-screen");

        assertEquals("level1-screen", ScreenRegistry.getScreenIdAt(serverLevel, pos1));
        assertEquals("level2-screen", ScreenRegistry.getScreenIdAt(anotherServer, pos1));

        List<BlockPos> l1 = ScreenRegistry.getPositionsForScreenId(serverLevel, "level1-screen");
        assertEquals(1, l1.size());

        List<BlockPos> l2 = ScreenRegistry.getPositionsForScreenId(anotherServer, "level2-screen");
        assertEquals(1, l2.size());

        ScreenRegistry.clearLevel(serverLevel);
        assertNull(ScreenRegistry.getScreenIdAt(serverLevel, pos1));
        assertEquals("level2-screen", ScreenRegistry.getScreenIdAt(anotherServer, pos1));
    }

    @Test
    void delegatesToHelper() {
        ScreenRegistry.setImageId("screen1", imageId);
        assertEquals(imageId, ScreenRegistry.getImageId("screen1"));

        ScreenRegistry.removeScreenId("screen1");
        assertNull(ScreenRegistry.getImageId("screen1"));
    }

    @Test
    void persistenceRoundTrip() {
        ScreenRegistryHelper primary = new ScreenRegistryHelper(
                org.slf4j.LoggerFactory.getLogger("test"));
        primary.init(savedTempDir);
        primary.setImageId("persist-screen", imageId);
        primary.saveRegistry();

        assertTrue(savedTempDir.resolve("screen_registry.json").toFile().exists(),
                "Registry file must exist after save");

        ScreenRegistryHelper freshHelper = new ScreenRegistryHelper(
                org.slf4j.LoggerFactory.getLogger("test"));
        freshHelper.init(savedTempDir);
        assertEquals(imageId, freshHelper.getImageId("persist-screen"));
    }

    @Test
    void anchorRedirect_delegatesToHelper() {
        BlockPos from = new BlockPos(100, 64, 100);
        BlockPos to = new BlockPos(101, 64, 100);

        ScreenRegistry.redirectAnchor(serverLevel, from, to);
        BlockPos resolved = ScreenRegistry.resolveAnchorRedirect(serverLevel, from);
        assertNotNull(resolved);
        assertEquals(to, resolved);

        ScreenRegistry.removeAnchorRedirect(serverLevel, from);
        assertNull(ScreenRegistry.resolveAnchorRedirect(serverLevel, from));
    }
}
