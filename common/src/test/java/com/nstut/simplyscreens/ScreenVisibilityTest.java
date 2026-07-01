package com.nstut.simplyscreens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenVisibilityTest {
    @Test
    void remainsVisibleWhenAnchorIsOutOfRangeButScreenEdgeIsNearCamera() {
        assertTrue(ScreenVisibility.isWithinDistance(
                0, 0, 0,
                100, 0, 0,
                1, 9, 0,
                16));
    }

    @Test
    void supportsStructuresExtendingInNegativeDirections() {
        assertTrue(ScreenVisibility.isWithinDistance(
                -25, 5, 0,
                0, 0, 0,
                -20, 10, 0,
                8));
    }

    @Test
    void rejectsScreenWhenEveryEdgeIsOutsideViewDistance() {
        assertFalse(ScreenVisibility.isWithinDistance(
                0, 0, 0,
                100, 100, 100,
                120, 120, 100,
                64));
    }
}
