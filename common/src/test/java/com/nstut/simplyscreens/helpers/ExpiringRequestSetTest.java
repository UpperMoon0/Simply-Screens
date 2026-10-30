package com.nstut.simplyscreens.helpers;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpiringRequestSetTest {
    @Test
    void suppressesDuplicatesUntilReleased() {
        ExpiringRequestSet<String> requests = new ExpiringRequestSet<>(Duration.ofSeconds(30));

        assertTrue(requests.tryAcquire("image"));
        assertFalse(requests.tryAcquire("image"));
        requests.release("image");
        assertTrue(requests.tryAcquire("image"));
    }

    @Test
    void permitsRetryAfterTimeout() {
        AtomicLong clock = new AtomicLong();
        ExpiringRequestSet<String> requests = new ExpiringRequestSet<>(Duration.ofSeconds(30), clock::get);

        assertTrue(requests.tryAcquire("image"));
        clock.set(Duration.ofSeconds(29).toNanos());
        assertFalse(requests.tryAcquire("image"));
        clock.set(Duration.ofSeconds(30).toNanos());
        assertTrue(requests.tryAcquire("image"));
    }

    @Test
    void failedDownloadHasShortBackoffIndependentOfThirtySecondInFlightTimeout() {
        AtomicLong clock = new AtomicLong();
        String image = "image";
        var inFlight = new TransferRequestCoordinator<>(
                new ExpiringRequestSet<String>(Duration.ofSeconds(30), clock::get));
        var retryBackoff = new ExpiringRequestSet<String>(Duration.ofSeconds(2), clock::get);
        assertTrue(inFlight.tryStart(image, () -> {}));

        // Failure releases the in-flight reservation and starts a separate cooldown.
        inFlight.release(image);
        assertTrue(retryBackoff.tryAcquire(image));
        assertTrue(retryBackoff.isPending(image));
        clock.set(Duration.ofMillis(1999).toNanos());
        assertTrue(retryBackoff.isPending(image), "No per-frame retry storm");
        clock.set(Duration.ofSeconds(2).toNanos());
        assertFalse(retryBackoff.isPending(image), "Retry is available after 2s, not 30s");
        assertTrue(inFlight.tryStart(image, () -> {}), "Retry may start immediately after cooldown");
        inFlight.release(image); // successful receipt
        assertTrue(inFlight.tryStart(image, () -> {}), "Successful transfers release their reservation");
    }

    @Test
    void boundsUniqueRequestStorms() {
        ExpiringRequestSet<Integer> requests =
                new ExpiringRequestSet<>(Duration.ofSeconds(30), System::nanoTime, 128);

        long accepted = IntStream.range(0, 10_000).filter(requests::tryAcquire).count();

        assertEquals(128, accepted);
        assertEquals(128, requests.size());
    }
}
