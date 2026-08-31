package com.nstut.simplyscreens.client.compat.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

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

        cache.clearValues();
        assertEquals(ClientScreenSpatialResolver.UNCACHED, cache.get(firstLevel, anchor));
    }

    @Test
    void primitiveCacheEvictsRemovedLevel() {
        Object firstLevel = new Object();
        Object secondLevel = new Object();
        long anchor = new BlockPos(10, 20, 30).asLong();
        ClientScreenSpatialResolver.FrameVisibilityCache<Object> cache =
                new ClientScreenSpatialResolver.FrameVisibilityCache<>();

        cache.put(firstLevel, anchor, Boolean.TRUE);
        cache.put(secondLevel, anchor, Boolean.FALSE);

        cache.removeLevel(firstLevel);

        assertEquals(ClientScreenSpatialResolver.UNCACHED, cache.get(firstLevel, anchor));
        assertEquals(ClientScreenSpatialResolver.HIDDEN, cache.get(secondLevel, anchor));
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

    @Test
    void cullGeometryCacheEvictsRemovedLevel() {
        Object firstLevel = new Object();
        Object secondLevel = new Object();
        BlockPos anchor = new BlockPos(10, 20, 30);
        long key = anchor.asLong();
        ClientScreenSpatialResolver.CullGeometryCache<Object> cache =
                new ClientScreenSpatialResolver.CullGeometryCache<>();

        cache.get(firstLevel, key, anchor, 32, 16, Direction.NORTH);
        cache.get(secondLevel, key, anchor, 32, 16, Direction.NORTH);

        cache.removeLevel(firstLevel);

        ClientScreenSpatialResolver.CullGeometry recreated =
                cache.get(firstLevel, key, anchor, 32, 16, Direction.NORTH);
        assertNotSame(recreated, cache.get(secondLevel, key, anchor, 32, 16, Direction.NORTH));
    }

    @Test
    void cullGeometryIsReusedUntilStructureSignatureChanges() {
        Object level = new Object();
        BlockPos anchor = new BlockPos(10, 20, 30);
        long key = anchor.asLong();
        ClientScreenSpatialResolver.CullGeometryCache<Object> cache =
                new ClientScreenSpatialResolver.CullGeometryCache<>();

        ClientScreenSpatialResolver.CullGeometry first =
                cache.get(level, key, anchor, 32, 16, Direction.NORTH);
        assertSame(first, cache.get(level, key, anchor, 32, 16, Direction.NORTH));

        ClientScreenSpatialResolver.CullGeometry resized =
                cache.get(level, key, anchor, 33, 16, Direction.NORTH);
        assertNotSame(first, resized);
        assertNotSame(resized, cache.get(level, key, anchor, 33, 16, Direction.SOUTH));
    }

    @Test
    void cullGeometryStoresCenterAndConservativeBaseRadius() {
        ClientScreenSpatialResolver.CullGeometry geometry = ClientScreenSpatialResolver.calculateGeometry(
                new BlockPos(2, 4, 6), 3, 2, Direction.NORTH);

        assertEquals(1.5, geometry.localCenter().x);
        assertEquals(5.0, geometry.localCenter().y);
        assertEquals(6.5, geometry.localCenter().z);
        assertEquals(Math.sqrt(3.5), geometry.baseRadius());
    }

    @Test
    void cullGeometryPrunesScreensNotSeenInTheCurrentRenderSession() {
        Object level = new Object();
        BlockPos anchor = new BlockPos(10, 20, 30);
        long key = anchor.asLong();
        ClientScreenSpatialResolver.CullGeometryCache<Object> cache =
                new ClientScreenSpatialResolver.CullGeometryCache<>();

        ClientScreenSpatialResolver.CullGeometry first =
                cache.get(level, key, anchor, 2, 2, Direction.NORTH);
        cache.beginFrame();
        cache.beginFrame();

        assertNotSame(first, cache.get(level, key, anchor, 2, 2, Direction.NORTH));
    }
}
