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
}
