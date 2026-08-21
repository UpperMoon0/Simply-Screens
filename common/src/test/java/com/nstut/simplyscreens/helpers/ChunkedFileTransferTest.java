package com.nstut.simplyscreens.helpers;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkedFileTransferTest {
    @Test
    void boundedExecutorRejectsWorkPastItsQueueCapacity() throws Exception {
        ExecutorService executor = ChunkedFileTransfer.newDaemonBoundedThreadPool(1, 1, "bounded-test");
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                running.countDown();
                try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            executor.execute(() -> { });
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
