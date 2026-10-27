package com.nstut.simplyscreens.helpers;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MediaDownloadRequestsTest {
    @Test void missingResponsesAndActivityHaveBoundedIdleLifetime() {
        var now = new AtomicLong();
        var requests = new MediaDownloadRequests(2, Duration.ofNanos(10), now::get);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), excess = UUID.randomUUID();
        UUID firstRequest = requests.begin(first);
        requests.begin(second);
        assertNull(requests.begin(excess));
        now.set(9);
        assertTrue(requests.touch(first, firstRequest));
        now.set(10);
        assertEquals(java.util.List.of(second), requests.reapExpired());
        assertNotNull(requests.begin(excess));
        assertEquals(firstRequest, requests.get(first));
        now.set(19);
        assertTrue(requests.reapExpired().contains(first));
        assertNull(requests.get(first));
    }
    @Test void staleCompletionCannotRemoveRetryIdentity() {
        var requests = new MediaDownloadRequests();
        UUID image = UUID.randomUUID(), stale = requests.begin(image), retry = requests.begin(image);
        assertFalse(requests.touch(image, stale));
        assertFalse(requests.remove(image, stale));
        assertEquals(retry, requests.get(image));
        assertTrue(requests.remove(image, retry));
        assertNull(requests.get(image));
    }
}
