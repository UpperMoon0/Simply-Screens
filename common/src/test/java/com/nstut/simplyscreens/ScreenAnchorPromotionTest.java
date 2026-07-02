package com.nstut.simplyscreens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScreenAnchorPromotionTest {
    @Test
    void singleScreenHasNoReplacement() {
        assertPromotion(1, 1, ScreenAnchorPromotion.Axis.NONE, 1, 1);
    }

    @Test
    void horizontalScreenPromotesAlongWidth() {
        assertPromotion(5, 2, ScreenAnchorPromotion.Axis.WIDTH, 4, 2);
        assertPromotion(4, 1, ScreenAnchorPromotion.Axis.WIDTH, 3, 1);
    }

    @Test
    void verticalScreenPromotesAlongHeight() {
        assertPromotion(2, 5, ScreenAnchorPromotion.Axis.HEIGHT, 2, 4);
        assertPromotion(1, 4, ScreenAnchorPromotion.Axis.HEIGHT, 1, 3);
    }

    @Test
    void squareTieUsesStableWidthPromotion() {
        assertPromotion(3, 3, ScreenAnchorPromotion.Axis.WIDTH, 2, 3);
    }

    private static void assertPromotion(int width, int height, ScreenAnchorPromotion.Axis axis,
                                        int remainingWidth, int remainingHeight) {
        assertEquals(new ScreenAnchorPromotion.Result(axis, remainingWidth, remainingHeight),
                ScreenAnchorPromotion.choose(width, height));
    }
}
