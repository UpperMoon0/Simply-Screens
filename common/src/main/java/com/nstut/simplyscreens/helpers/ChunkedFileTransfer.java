package com.nstut.simplyscreens.helpers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChunkedFileTransfer {
    public static final int CHUNK_SIZE = 30 * 1024;
    private ChunkedFileTransfer() {
    }

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(int chunkIndex, int totalChunks, byte[] chunk) throws IOException;
    }

    public static ExecutorService newDaemonFixedThreadPool(int threads, String threadName) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    public static void streamFile(Path filePath, int chunkSize, ChunkConsumer consumer) throws IOException {
        long fileSize = Files.size(filePath);
        int totalChunks = Math.max(1, (int) Math.ceil((double) fileSize / chunkSize));

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[chunkSize];
            int chunkIndex = 0;
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                consumer.accept(chunkIndex++, totalChunks, chunk);
            }

            if (chunkIndex == 0) {
                consumer.accept(0, totalChunks, new byte[0]);
            }
        }
    }
}
