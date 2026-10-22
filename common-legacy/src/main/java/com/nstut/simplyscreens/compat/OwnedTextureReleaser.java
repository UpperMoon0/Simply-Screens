package com.nstut.simplyscreens.compat;

import java.util.Map;

/** Detach only the expected owned resource before releasing its native allocation. */
public final class OwnedTextureReleaser {
    private OwnedTextureReleaser() {}
    public static <K, T> boolean release(Map<K, T> registry, K key, T expected, Runnable close) {
        if (expected == null || registry.get(key) != expected) return false;
        registry.remove(key);
        close.run();
        return true;
    }
}
