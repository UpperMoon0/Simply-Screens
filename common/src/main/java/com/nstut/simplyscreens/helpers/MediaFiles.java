package com.nstut.simplyscreens.helpers;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Publish a complete file, preserving the previous cache on write failure. */
public final class MediaFiles {
    private MediaFiles() { }
    public static boolean isImageExtension(String extension) {
        return "png".equals(extension) || "jpg".equals(extension) || "jpeg".equals(extension);
    }
    public static byte[] readBounded(java.io.InputStream input, int limit) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer, 0, (int) Math.min(buffer.length, (long) limit - total + 1))) != -1) {
            total += read;
            if (total > limit) throw new IOException("Image exceeds encoded byte budget");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
    /** Decode before publishing: rejected image data must never poison a durable cache entry. */
    public static <T extends AutoCloseable> T decodeAndWriteAtomically(
            Path destination, byte[] bytes, Decoder<T> decoder) throws IOException {
        T decoded = decoder.decode(bytes);
        try {
            writeAtomically(destination, bytes);
            return decoded;
        } catch (IOException | RuntimeException failure) {
            try { decoded.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @FunctionalInterface
    public interface Decoder<T> {
        T decode(byte[] bytes) throws IOException;
    }

    /**
     * Decode and stage outside the publication lock. Only the final generation check and
     * atomic rename hold the lock shared with cache invalidation and peer transitions.
     * Returns null for a cancelled transfer, closing its decoded image and deleting its
     * temporary file. A cancelled worker never replaces a newer cache entry.
     */
    public static <T extends AutoCloseable> T decodeAndWriteAtomically(
            Path destination, byte[] bytes, Decoder<T> decoder,
            Object publicationLock, java.util.function.BooleanSupplier mayPublish) throws IOException {
        T decoded = decoder.decode(bytes);
        boolean handedOff = false;
        try {
            Path staged = stage(destination, bytes);
            boolean published = false;
            try {
                synchronized (publicationLock) {
                    if (mayPublish.getAsBoolean()) {
                        publish(staged, destination);
                        published = true;
                    }
                }
            } finally {
                Files.deleteIfExists(staged);
            }
            if (!published) return null;
            handedOff = true;
            return decoded;
        } finally {
            if (!handedOff) {
                // An invalidation or failed staging/publication owns no texture.
                try { decoded.close(); } catch (Exception ignored) { }
            }
        }
    }

    public static void writeAtomically(Path destination, byte[] bytes) throws IOException {
        Path staged = stage(destination, bytes);
        try { publish(staged, destination); }
        finally { Files.deleteIfExists(staged); }
    }

    private static Path stage(Path destination, byte[] bytes) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".simply-screens-", ".part");
        try {
            Files.write(temporary, bytes);
            return temporary;
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void publish(Path staged, Path destination) throws IOException {
        try { Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
