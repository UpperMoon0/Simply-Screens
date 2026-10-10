package com.nstut.simplyscreens.helpers;

import com.nstut.packetweave.api.ChunkedTransfer;
import com.nstut.packetweave.api.TransferLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MediaDownloadProgressTest {
    @TempDir Path directory;

    @Test void progressingStreamCannotOverlapItsRequestTimeoutRetry() throws Exception {
        AtomicLong clock = new AtomicLong();
        Duration timeout = Duration.ofSeconds(30);
        MediaDownloadReceiver receiver = new MediaDownloadReceiver(
                new TransferLimits(4, 16, 1, 1, 16, timeout), clock::get);
        TransferRequestCoordinator<UUID> requests = new TransferRequestCoordinator<>(
                new ExpiringRequestSet<>(timeout, clock::get));
        WorkerTransferCoordinator<UUID> workers = new WorkerTransferCoordinator<>(4);
        BlockingQueue<Runnable> gameThread = new LinkedBlockingQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        UUID image = UUID.randomUUID();
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<MediaDownloadReceiver.Download> completed = new AtomicReference<>();
        CompletableFuture<Void> finished = new CompletableFuture<>();
        byte[] bytes = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16};
        Path file = directory.resolve("image.png");
        Files.write(file, bytes);
        Runnable send = () -> {
            starts.incrementAndGet();
            try {
                ChunkedTransfer.streamFile(file, 4, 16,
                        ChunkedTransfer.onExecutor(gameThread::add, Duration.ofSeconds(5),
                                (index, count, data) -> {
                                    var download = receiver.accept(image, index, count, data, index == 0 ? "png" : null);
                                    if (download != null) completed.set(download);
                                }));
                finished.complete(null);
            } catch (Throwable failure) { finished.completeExceptionally(failure); }
        };
        try {
            assertTrue(receiver.requestIfIdle(image, requests, () -> assertTrue(workers.tryStart(image, pool, send))));
            // The production sender awaits each real dispatch; time advances without sleeping.
            for (int seconds : new int[]{0, 10, 20}) {
                clock.set(Duration.ofSeconds(seconds).toNanos());
                Runnable dispatch = gameThread.poll(5, TimeUnit.SECONDS);
                assertNotNull(dispatch);
                dispatch.run();
            }
            clock.set(Duration.ofSeconds(31).toNanos());
            assertTrue(receiver.isPending(image));
            assertFalse(receiver.requestIfIdle(image, requests, () -> workers.tryStart(image, pool, send)));
            // Even a duplicate/malicious request must not overlap the still-running server worker.
            assertFalse(workers.tryStart(image, pool, send));
            Runnable last = gameThread.poll(5, TimeUnit.SECONDS);
            assertNotNull(last);
            last.run();
            finished.get(5, TimeUnit.SECONDS);
            assertEquals(1, starts.get());
            assertNotNull(completed.get());
            assertArrayEquals(bytes, completed.get().bytes());
            assertFalse(receiver.isPending(image));
            requests.release(image);
            assertTrue(receiver.requestIfIdle(image, requests, () -> {}));
        } finally { pool.shutdownNow(); }
    }

    @Test void missingResponseAndExpiredReceiverStillAllowRetry() {
        AtomicLong clock = new AtomicLong();
        Duration timeout = Duration.ofSeconds(30);
        MediaDownloadReceiver receiver = new MediaDownloadReceiver(
                new TransferLimits(4, 8, 1, 1, 8, timeout), clock::get);
        TransferRequestCoordinator<UUID> requests = new TransferRequestCoordinator<>(
                new ExpiringRequestSet<>(timeout, clock::get));
        UUID image = UUID.randomUUID();
        AtomicInteger starts = new AtomicInteger();
        assertTrue(receiver.requestIfIdle(image, requests, starts::incrementAndGet));
        assertFalse(receiver.requestIfIdle(image, requests, starts::incrementAndGet));
        clock.set(Duration.ofSeconds(31).toNanos());
        assertTrue(receiver.requestIfIdle(image, requests, starts::incrementAndGet));
        receiver.accept(image, 0, 2, new byte[]{1,2,3,4}, "png");
        clock.set(Duration.ofSeconds(62).toNanos());
        for (UUID expired : receiver.reapExpired()) requests.release(expired);
        assertTrue(receiver.requestIfIdle(image, requests, starts::incrementAndGet));
        assertEquals(3, starts.get());
    }
}
