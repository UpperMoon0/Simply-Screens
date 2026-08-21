package com.nstut.simplyscreens;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

@MockitoSettings
class ScreenRegistryHelperTest {

    private ScreenRegistryHelper helper;

    @Mock
    private Logger logger;

    @BeforeEach
    void setUp() {
        helper = new ScreenRegistryHelper(logger);
    }

    // 1.1 init creates file path and loads existing registry
    @Test
    void init_setsInitializedAndLoads(@TempDir Path tempDir) {
        assertFalse(helper.isInitialized());
        helper.init(tempDir);
        assertTrue(helper.isInitialized());
    }

    // 1.2 init is idempotent
    @Test
    void init_isIdempotent(@TempDir Path tempDir) {
        helper.init(tempDir);
        assertTrue(helper.isInitialized());

        ScreenRegistryHelper helper2 = new ScreenRegistryHelper(logger);
        helper2.init(tempDir);
        assertTrue(helper2.isInitialized(), "Second init should keep initialized flag true");
    }

    // 1.3 setImageId stores mapping
    @Test
    void setImageId_storesMapping() {
        UUID imageId = UUID.randomUUID();
        helper.setImageId("screen1", imageId);
        assertEquals(imageId, helper.getImageId("screen1"));
    }

    // 1.4 setImageId with null imageId removes entry
    @Test
    void setImageId_nullRemovesEntry() {
        UUID imageId = UUID.randomUUID();
        helper.setImageId("screen1", imageId);
        assertNotNull(helper.getImageId("screen1"));

        helper.setImageId("screen1", null);
        assertNull(helper.getImageId("screen1"));
    }

    // 1.5 setImageId with null/empty screenId is no-op
    @Test
    void setImageId_nullScreenIdDoesNothing() {
        UUID imageId = UUID.randomUUID();
        helper.setImageId(null, imageId);
        assertTrue(helper.getAllScreenIds().isEmpty());
    }

    @Test
    void setImageId_emptyScreenIdDoesNothing() {
        UUID imageId = UUID.randomUUID();
        helper.setImageId("", imageId);
        assertTrue(helper.getAllScreenIds().isEmpty());
    }

    // 1.6 getImageId returns null for unregistered ID
    @Test
    void getImageId_returnsNullForUnknown() {
        assertNull(helper.getImageId("nonexistent"));
    }

    // 1.7 removeScreenId removes existing mapping
    @Test
    void removeScreenId_removesMapping() {
        UUID imageId = UUID.randomUUID();
        helper.setImageId("screen1", imageId);
        assertNotNull(helper.getImageId("screen1"));

        helper.removeScreenId("screen1");
        assertNull(helper.getImageId("screen1"));
    }

    @Test
    void removeImageReferencesClearsEveryMatchingLink() {
        UUID removed = UUID.randomUUID();
        UUID retained = UUID.randomUUID();
        helper.setImageId("first", removed);
        helper.setImageId("second", removed);
        helper.setImageId("other", retained);
        assertTrue(helper.removeImageReferences(removed));
        assertNull(helper.getImageId("first"));
        assertNull(helper.getImageId("second"));
        assertEquals(retained, helper.getImageId("other"));
    }

    @Test
    void repeatedSaveCreatesRegistryBackups(@TempDir Path tempDir) {
        helper.init(tempDir);
        helper.setImageId("first", UUID.randomUUID());
        helper.saveRegistry();
        helper.setImageId("second", UUID.randomUUID());
        helper.saveRegistry();
        assertTrue(Files.exists(tempDir.resolve("screen_registry.json.bak")));
        assertTrue(Files.exists(tempDir.resolve("screen_registry_owners.json.bak")));
    }

    // 1.8 getAllScreenIds returns all IDs
    @Test
    void getAllScreenIds_returnsAllEntries() {
        helper.setImageId("a", UUID.randomUUID());
        helper.setImageId("b", UUID.randomUUID());
        helper.setImageId("c", UUID.randomUUID());
        Set<String> ids = helper.getAllScreenIds();
        assertEquals(3, ids.size());
        assertTrue(ids.containsAll(Set.of("a", "b", "c")));
    }

    // 1.9 saveRegistry writes JSON file
    @Test
    void saveRegistry_writesFile(@TempDir Path tempDir) throws IOException {
        helper.init(tempDir);
        UUID imageId = UUID.randomUUID();
        helper.setImageId("screen1", imageId);
        helper.saveRegistry();

        Path registryFile = tempDir.resolve("screen_registry.json");
        assertTrue(registryFile.toFile().exists());
        String content = new String(java.nio.file.Files.readAllBytes(registryFile));
        assertTrue(content.contains("screen1"));
        assertTrue(content.contains(imageId.toString()));
    }

    // 1.10 loadRegistry reads back saved state
    @Test
    void loadRegistry_restoresSavedState(@TempDir Path tempDir) {
        helper.init(tempDir);
        UUID imageId = UUID.randomUUID();
        helper.setImageId("screen1", imageId);
        helper.saveRegistry();

        ScreenRegistryHelper helper2 = new ScreenRegistryHelper(logger);
        helper2.init(tempDir);
        assertEquals(imageId, helper2.getImageId("screen1"));
    }

    // 1.11 loadRegistry handles invalid UUID gracefully
    @Test
    void loadRegistry_handlesInvalidUUID(@TempDir Path tempDir) throws IOException {
        Path registryFile = tempDir.resolve("screen_registry.json");
        try (FileWriter writer = new FileWriter(registryFile.toFile())) {
            writer.write("{\"bad-screen\": \"not-a-uuid\"}");
        }

        ScreenRegistryHelper h = new ScreenRegistryHelper(logger);
        h.init(tempDir);
        assertNull(h.getImageId("bad-screen"));
        assertTrue(h.getAllScreenIds().isEmpty());
    }

    // 1.12 loadRegistry with missing file starts empty
    @Test
    void loadRegistry_missingFileStartsEmpty(@TempDir Path tempDir) {
        ScreenRegistryHelper h = new ScreenRegistryHelper(logger);
        h.init(tempDir);
        assertTrue(h.getAllScreenIds().isEmpty());
    }

    // 1.13 Concurrent access safety
    @Test
    void concurrentAccess_isThreadSafe() throws Exception {
        int threadCount = 8;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ConcurrentLinkedQueue<Exception> exceptions = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = "screen-" + threadId + "-" + i;
                        UUID uuid = UUID.randomUUID();
                        helper.setImageId(key, uuid);
                        assertEquals(uuid, helper.getImageId(key));
                        helper.removeScreenId(key);
                        assertNull(helper.getImageId(key));
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(exceptions.isEmpty(),
                "Concurrent access caused exceptions: " + exceptions);
    }

    @Test
    void screenIdOwnershipPreventsCrossPlayerWritesAndPersists(@TempDir Path tempDir) {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        helper.init(tempDir);
        assertTrue(helper.claimScreenId("private-link", owner, false));
        helper.saveRegistry();

        ScreenRegistryHelper reloaded = new ScreenRegistryHelper(logger);
        reloaded.init(tempDir);
        assertTrue(reloaded.canWriteScreenId("private-link", owner, false));
        assertFalse(reloaded.canWriteScreenId("private-link", intruder, false));
        assertTrue(reloaded.canWriteScreenId("private-link", intruder, true));
    }

    @Test
    void legacyOwnerlessIdRequiresAdministratorClaim() {
        UUID player = UUID.randomUUID();
        helper.setImageId("legacy", UUID.randomUUID());
        assertFalse(helper.claimScreenId("legacy", player, false));
        assertTrue(helper.claimScreenId("legacy", player, true));
    }

    @Test
    void screenIdsAreNormalizedAndInvalidValuesAreRejected() {
        UUID player = UUID.randomUUID();
        assertEquals("valid-link_1", ScreenRegistryHelper.normalizeScreenId("  valid-link_1  "));
        assertEquals("", ScreenRegistryHelper.normalizeScreenId("   "));
        assertEquals("", ScreenRegistryHelper.normalizeScreenId("contains spaces"));
        assertEquals("", ScreenRegistryHelper.normalizeScreenId("x".repeat(65)));
        assertFalse(helper.claimScreenId("   ", player, false));
        assertFalse(helper.claimScreenId("contains spaces", player, false));
    }

    @Test
    void playersCannotReserveUnlimitedScreenIds() {
        UUID player = UUID.randomUUID();
        for (int i = 0; i < ScreenRegistryHelper.MAX_SCREEN_IDS_PER_PLAYER; i++) {
            assertTrue(helper.claimScreenId("link-" + i, player, false));
        }
        assertFalse(helper.claimScreenId("one-too-many", player, false));
    }

    @Test
    void anchorRedirect_storesAndResolvesDirect() {
        helper.redirectAnchor("minecraft:overworld", 0, 64, 0, 1, 64, 0);
        int[] resolved = helper.resolveAnchorRedirect("minecraft:overworld", 0, 64, 0);
        assertNotNull(resolved);
        assertArrayEquals(new int[]{1, 64, 0}, resolved);
    }

    @Test
    void anchorRedirect_resolvesChainedAndPathCompresses() {
        helper.redirectAnchor("minecraft:overworld", 0, 64, 0, 1, 64, 0);
        helper.redirectAnchor("minecraft:overworld", 1, 64, 0, 2, 64, 0);

        int[] resolved = helper.resolveAnchorRedirect("minecraft:overworld", 0, 64, 0);
        assertNotNull(resolved);
        assertArrayEquals(new int[]{2, 64, 0}, resolved);

        // Chained resolution should have compressed shortcut
        assertEquals("2,64,0", helper.resolveAnchorRedirectString("minecraft:overworld", "0,64,0"));
    }

    @Test
    void anchorRedirect_handlesCyclesSafely() {
        helper.redirectAnchor("minecraft:overworld", 0, 64, 0, 1, 64, 0);
        helper.redirectAnchor("minecraft:overworld", 1, 64, 0, 0, 64, 0);

        int[] resolved = helper.resolveAnchorRedirect("minecraft:overworld", 0, 64, 0);
        assertNotNull(resolved);
        // Returns the last valid target reached before the cycle was detected
        assertArrayEquals(new int[]{1, 64, 0}, resolved);
    }

    @Test
    void anchorRedirect_removeAndClear() {
        helper.redirectAnchor("minecraft:overworld", 0, 64, 0, 1, 64, 0);
        helper.redirectAnchor("minecraft:the_nether", 10, 50, 10, 20, 50, 20);

        helper.removeAnchorRedirect("minecraft:overworld", 0, 64, 0);
        assertNull(helper.resolveAnchorRedirect("minecraft:overworld", 0, 64, 0));
        assertNotNull(helper.resolveAnchorRedirect("minecraft:the_nether", 10, 50, 10));

        helper.clearAnchorRedirects("minecraft:the_nether");
        assertNull(helper.resolveAnchorRedirect("minecraft:the_nether", 10, 50, 10));
    }

    @Test
    void anchorRedirect_persistsAndLoadsFromDisk(@TempDir Path tempDir) {
        helper.init(tempDir);
        helper.redirectAnchor("minecraft:overworld", 5, 70, 5, 6, 70, 5);
        helper.saveRegistry();

        Path redirectFile = tempDir.resolve("anchor_redirects.json");
        assertTrue(Files.exists(redirectFile));

        ScreenRegistryHelper helper2 = new ScreenRegistryHelper(logger);
        helper2.init(tempDir);
        int[] resolved = helper2.resolveAnchorRedirect("minecraft:overworld", 5, 70, 5);
        assertNotNull(resolved);
        assertArrayEquals(new int[]{6, 70, 5}, resolved);
    }

    @Test
    void loadRegistry_recoversFromBackupWhenPrimaryCorruptOrMissing(@TempDir Path tempDir) throws IOException {
        helper.init(tempDir);
        UUID imageId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        assertTrue(helper.claimScreenId("screen_main", ownerId, false));
        helper.setImageId("screen_main", imageId);
        helper.redirectAnchor("minecraft:overworld", 1, 2, 3, 4, 5, 6);
        helper.saveRegistry();
        // Save again to generate .bak files
        helper.saveRegistry();

        assertTrue(Files.exists(tempDir.resolve("screen_registry.json.bak")));
        assertTrue(Files.exists(tempDir.resolve("screen_registry_owners.json.bak")));
        assertTrue(Files.exists(tempDir.resolve("anchor_redirects.json.bak")));

        // Corrupt primary files
        Files.writeString(tempDir.resolve("screen_registry.json"), "{ corrupted json content");
        Files.delete(tempDir.resolve("screen_registry_owners.json"));
        Files.writeString(tempDir.resolve("anchor_redirects.json"), "invalid json data");

        // Reload into fresh helper
        ScreenRegistryHelper helper2 = new ScreenRegistryHelper(logger);
        helper2.init(tempDir);

        assertEquals(imageId, helper2.getImageId("screen_main"), "Should recover imageId from backup");
        assertEquals(ownerId, helper2.getScreenIdOwner("screen_main"), "Should recover owner from backup");
        int[] resolved = helper2.resolveAnchorRedirect("minecraft:overworld", 1, 2, 3);
        assertNotNull(resolved, "Should recover anchor redirect from backup");
        assertArrayEquals(new int[]{4, 5, 6}, resolved);
    }
}
