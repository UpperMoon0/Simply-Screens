package com.nstut.simplyscreens.compat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedTextureReleaserTest {
    @Test void repeatedEvictionInvalidationAndDisconnectRemainBounded() {
        Map<String, Object> registry = new HashMap<>();
        AtomicInteger closed = new AtomicInteger();
        for (int cycle = 0; cycle < 8; cycle++) {
            for (int image = 0; image < 100; image++) {
                String key = "image-" + image;
                Object texture = new Object();
                registry.put(key, texture);
                assertTrue(OwnedTextureReleaser.release(registry, key, texture, closed::incrementAndGet));
                assertTrue(registry.isEmpty());
                assertFalse(OwnedTextureReleaser.release(registry, key, texture, closed::incrementAndGet));
            }
        }
        assertEquals(800, closed.get());
    }
    @Test void removalHappensBeforeCloseAndOnlyExpectedIdentityIsReleased() {
        Map<String, Object> registry = new HashMap<>();
        Object owned = new Object(), replacement = new Object();
        registry.put("image", replacement);
        assertFalse(OwnedTextureReleaser.release(registry, "image", owned, () -> fail("foreign texture closed")));
        assertSame(replacement, registry.get("image"));
        assertTrue(OwnedTextureReleaser.release(registry, "image", replacement, () -> assertFalse(registry.containsKey("image"))));
    }
    @Test void failedCloseCannotLeaveAStaleRegistrationToDeleteLater() {
        Map<String, Object> registry = new HashMap<>();Object texture = new Object();registry.put("image",texture);
        assertThrows(IllegalStateException.class, () -> OwnedTextureReleaser.release(registry,"image",texture,() -> { throw new IllegalStateException("fixture"); }));
        assertTrue(registry.isEmpty());
        assertFalse(OwnedTextureReleaser.release(registry,"image",texture,() -> fail("closed twice")));
    }
    @Test void missingOwnershipNeverClosesAnything() {
        assertFalse(OwnedTextureReleaser.release(new HashMap<>(),"image",null,() -> fail("unowned")));
    }
}
