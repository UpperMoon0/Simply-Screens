package com.nstut.simplyscreens.client.compat.sable;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClientScreenSpatialResolverTest {
    @Test
    void radiusUsesLargestAbsoluteScaleComponent() {
        assertEquals(6.0,
                ClientScreenSpatialResolver.scaledRadius(2.0, new Vector3d(-1.5, 3.0, 0.5)));
    }

    @Test
    void visibilityIsComputedOncePerLogicalScreenPerRenderFrame() {
        Object levelIdentity = new Object();
        BlockPos anchor = new BlockPos(1, 2, 3);
        BlockPos farCorner = new BlockPos(4, 5, 6);
        AtomicInteger computations = new AtomicInteger();

        ClientScreenSpatialResolver.beginRenderFrame();
        assertEquals(Boolean.TRUE, ClientScreenSpatialResolver.cachedVisibility(
                levelIdentity, anchor, farCorner, 64, () -> computations.incrementAndGet() > 0));
        assertEquals(Boolean.TRUE, ClientScreenSpatialResolver.cachedVisibility(
                levelIdentity, anchor, farCorner, 64, () -> computations.incrementAndGet() > 0));
        assertEquals(1, computations.get());

        ClientScreenSpatialResolver.beginRenderFrame();
        assertEquals(Boolean.TRUE, ClientScreenSpatialResolver.cachedVisibility(
                levelIdentity, anchor, farCorner, 64, () -> computations.incrementAndGet() > 0));
        assertEquals(2, computations.get());
    }

    @Test
    void vanillaFallbackIsAlsoCachedWithinFrame() {
        Object levelIdentity = new Object();
        BlockPos anchor = BlockPos.ZERO;
        BlockPos farCorner = BlockPos.ZERO;
        AtomicInteger computations = new AtomicInteger();

        ClientScreenSpatialResolver.beginRenderFrame();
        assertNull(ClientScreenSpatialResolver.cachedVisibility(
                levelIdentity, anchor, farCorner, 64, () -> {
                    computations.incrementAndGet();
                    return null;
                }));
        assertNull(ClientScreenSpatialResolver.cachedVisibility(
                levelIdentity, anchor, farCorner, 64, () -> {
                    computations.incrementAndGet();
                    return null;
                }));
        assertEquals(1, computations.get());
    }
}
