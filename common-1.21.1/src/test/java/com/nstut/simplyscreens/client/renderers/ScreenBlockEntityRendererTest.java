package com.nstut.simplyscreens.client.renderers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenBlockEntityRendererTest {
    @Test
    void logicalScreenIsClaimedOncePerLevelAndFrame() {
        Object firstLevel = new Object();
        Object secondLevel = new Object();
        ScreenBlockEntityRenderer.FrameRenderClaims<Object> claims =
                new ScreenBlockEntityRenderer.FrameRenderClaims<>();

        assertTrue(claims.claim(firstLevel, 42L));
        assertFalse(claims.claim(firstLevel, 42L));
        assertTrue(claims.claim(secondLevel, 42L));

        claims.clearValues();
        assertTrue(claims.claim(firstLevel, 42L));
    }

    @Test
    void claimsEvictRemovedLevel() {
        Object firstLevel = new Object();
        Object secondLevel = new Object();
        ScreenBlockEntityRenderer.FrameRenderClaims<Object> claims =
                new ScreenBlockEntityRenderer.FrameRenderClaims<>();

        assertTrue(claims.claim(firstLevel, 42L));
        assertTrue(claims.claim(secondLevel, 42L));

        claims.removeLevel(firstLevel);

        assertTrue(claims.claim(firstLevel, 42L));
        assertFalse(claims.claim(secondLevel, 42L));
    }
}
