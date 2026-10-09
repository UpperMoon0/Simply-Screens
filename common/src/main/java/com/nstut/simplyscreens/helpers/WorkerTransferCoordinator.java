package com.nstut.simplyscreens.helpers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Bounded duplicate suppression for the entire queued/running lifetime of a worker. */
public final class WorkerTransferCoordinator<K> {
    private final Map<K, Object> active = new HashMap<>();
    private final int capacity;

    public WorkerTransferCoordinator(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public boolean tryStart(K key, Executor executor, Runnable worker) {
        Object lease = new Object();
        synchronized (active) {
            if (active.size() >= capacity || active.containsKey(key)) return false;
            active.put(key, lease);
        }
        try {
            executor.execute(() -> {
                try { worker.run(); }
                finally { release(key, lease); }
            });
            return true;
        } catch (RuntimeException | Error exception) {
            // An inline executor may already have finished and allowed a replacement worker.
            release(key, lease);
            throw exception;
        }
    }

    private void release(K key, Object lease) {
        synchronized (active) { active.remove(key, lease); }
    }
}
