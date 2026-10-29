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

    public static void writeAtomically(Path destination, byte[] bytes) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".simply-screens-", ".part");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
