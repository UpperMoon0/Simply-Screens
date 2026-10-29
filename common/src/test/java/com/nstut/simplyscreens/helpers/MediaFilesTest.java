package com.nstut.simplyscreens.helpers;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class MediaFilesTest {
    @TempDir Path directory;
    @Test void imageCacheExtensionsCannotSelectOtherFilesystemPaths() {
        for (String extension : new String[]{null, "../png", "png/../../other", "PNG", "json", ""})
            assertFalse(MediaFiles.isImageExtension(extension));
        for (String extension : new String[]{"png", "jpg", "jpeg"}) assertTrue(MediaFiles.isImageExtension(extension));
    }
    @Test void encodedInputReadStopsBeforeAllocatingBeyondItsBudget() throws Exception {
        var stream = new java.io.ByteArrayInputStream(new byte[100]);
        assertThrows(java.io.IOException.class, () -> MediaFiles.readBounded(stream, 4));
        assertEquals(95, stream.available());
        assertArrayEquals(new byte[]{1,2,3}, MediaFiles.readBounded(new java.io.ByteArrayInputStream(new byte[]{1,2,3}), 3));
    }
    @Test void publishesCompleteReplacementAndRemovesTemporaryFiles() throws Exception {
        Path target = directory.resolve("image.png");
        Files.write(target, new byte[]{1,2});
        byte[] bytes = new byte[4097];
        bytes[4096] = 7;
        MediaFiles.writeAtomically(target, bytes);
        assertArrayEquals(bytes, Files.readAllBytes(target));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void corruptCompleteDownloadCannotPoisonCacheAndValidRetrySucceeds() throws Exception {
        Path target = directory.resolve("retriable.png");
        byte[] corrupt = {1, 2, 3, 4};
        var output = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                "png", output));
        byte[] valid = output.toByteArray();

        assertThrows(java.io.IOException.class, () -> MediaFiles.decodeAndWriteAtomically(
                target, corrupt, MediaFilesTest::decodeImage));
        assertFalse(Files.exists(target), "Malformed complete transfers must not create a cache file");

        try (var decoded = MediaFiles.decodeAndWriteAtomically(target, valid, MediaFilesTest::decodeImage)) {
            assertArrayEquals(valid, Files.readAllBytes(target));
        }
        assertThrows(java.io.IOException.class, () -> MediaFiles.decodeAndWriteAtomically(
                target, corrupt, MediaFilesTest::decodeImage));
        assertArrayEquals(valid, Files.readAllBytes(target), "A later corrupt transfer must preserve the valid cache");
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void failedPublicationClosesDecodedImageAndPreservesExistingFiles() throws Exception {
        Path target = directory.resolve("occupied");
        Files.createDirectory(target);
        Files.write(target.resolve("preserved"), new byte[]{42});
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(java.io.IOException.class, () -> MediaFiles.decodeAndWriteAtomically(
                target, new byte[]{1}, bytes -> (AutoCloseable) () -> closed.set(true)));
        assertTrue(closed.get(), "A decoded native image must be closed when publication fails");
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.resolve("preserved")));
    }

    private static AutoCloseable decodeImage(byte[] bytes) throws java.io.IOException {
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (image == null) throw new java.io.IOException("Invalid image encoding");
        return image::flush;
    }

    @Test void failedPublicationCleansTemporaryFileAndPreservesDestination() throws Exception {
        Path target = directory.resolve("occupied");
        Files.createDirectory(target);
        Files.write(target.resolve("preserved"), new byte[]{42});
        assertThrows(java.io.IOException.class, () -> MediaFiles.writeAtomically(target, new byte[]{1}));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(target.resolve("preserved")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
}
