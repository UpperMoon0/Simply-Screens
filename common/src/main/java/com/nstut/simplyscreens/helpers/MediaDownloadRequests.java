package com.nstut.simplyscreens.helpers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded request identities, including requests which never receive a first chunk. */
public final class MediaDownloadRequests {
    private final Map<UUID, Request> requests = new HashMap<>();
    private final int limit;
    private final long timeout;
    private final LongSupplier clock;
    public MediaDownloadRequests() { this(32, Duration.ofSeconds(30), System::nanoTime); }
    MediaDownloadRequests(int limit, Duration timeout, LongSupplier clock) {
        this.limit = limit; this.timeout = timeout.toNanos(); this.clock = clock;
    }
    public synchronized UUID begin(UUID image) {
        if (requests.size() >= limit && !requests.containsKey(image)) return null;
        UUID id = UUID.randomUUID();
        requests.put(image, new Request(id, clock.getAsLong()));
        return id;
    }
    public synchronized UUID get(UUID image) {
        Request request = requests.get(image);
        return request == null ? null : request.id;
    }
    public synchronized boolean touch(UUID image, UUID id) {
        Request request = requests.get(image);
        if (request == null || !request.id.equals(id)) return false;
        request.activity = clock.getAsLong();
        return true;
    }
    public synchronized void remove(UUID image) { requests.remove(image); }
    public synchronized boolean remove(UUID image, UUID id) {
        if (!id.equals(get(image))) return false;
        requests.remove(image); return true;
    }
    public synchronized void clear() { requests.clear(); }
    public synchronized List<UUID> reapExpired() {
        long now = clock.getAsLong();
        List<UUID> expired = new ArrayList<>();
        requests.entrySet().removeIf(entry -> {
            if (now - entry.getValue().activity < timeout) return false;
            expired.add(entry.getKey()); return true;
        });
        return expired;
    }
    private static final class Request {
        final UUID id;
        long activity;
        Request(UUID id, long activity) { this.id = id; this.activity = activity; }
    }
}
