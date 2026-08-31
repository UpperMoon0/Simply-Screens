package com.nstut.simplyscreens.client.compat.sable;

import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClientScreenSpatialResolverTest {
    @Test
    void radiusUsesLargestAbsoluteScaleComponent() {
        assertEquals(6.0,
                ClientScreenSpatialResolver.scaledRadius(2.0, new Vector3d(-1.5, 3.0, 0.5)));
    }

    @Test
    void primitiveCacheSeparatesLevelsAndClearsBetweenFrames() {
        Object firstLevel = new Object();
        Object secondLevel = new Object();
        long anchor = new BlockPos(10, 20, 30).asLong();
        ClientScreenSpatialResolver.FrameVisibilityCache<Object> cache =
                new ClientScreenSpatialResolver.FrameVisibilityCache<>();

        assertEquals(ClientScreenSpatialResolver.UNCACHED, cache.get(firstLevel, anchor));
        cache.put(firstLevel, anchor, Boolean.TRUE);
        cache.put(secondLevel, anchor, Boolean.FALSE);
        assertEquals(ClientScreenSpatialResolver.VISIBLE, cache.get(firstLevel, anchor));
        assertEquals(ClientScreenSpatialResolver.HIDDEN, cache.get(secondLevel, anchor));

        cache.clear();
        assertEquals(ClientScreenSpatialResolver.UNCACHED, cache.get(firstLevel, anchor));
    }

    @Test
    void primitiveCachePreservesNullableVanillaFallback() {
        Object level = new Object();
        long anchor = new BlockPos(1, 2, 3).asLong();
        ClientScreenSpatialResolver.FrameVisibilityCache<Object> cache =
                new ClientScreenSpatialResolver.FrameVisibilityCache<>();

        cache.put(level, anchor, null);
        byte cached = cache.get(level, anchor);
        assertEquals(ClientScreenSpatialResolver.VANILLA, cached);
        assertNull(ClientScreenSpatialResolver.decode(cached));
    }
}
