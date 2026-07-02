package com.nstut.simplyscreens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void promotionPreservesTheLargestAdjacentRectangleForAllPracticalSizes() {
        for (int width = 1; width <= 256; width++) {
            for (int height = 1; height <= 256; height++) {
                ScreenAnchorPromotion.Result result = ScreenAnchorPromotion.choose(width, height);
                int preservedArea = result.width() * result.height();
                int bestPossibleArea = Math.max(Math.max(0, width - 1) * height,
                        width * Math.max(0, height - 1));

                if (width == 1 && height == 1) {
                    assertEquals(ScreenAnchorPromotion.Axis.NONE, result.axis());
                } else {
                    assertTrue(result.axis() != ScreenAnchorPromotion.Axis.NONE);
                    assertEquals(bestPossibleArea, preservedArea,
                            "non-maximal promotion for " + width + "x" + height);
                }
            }
        }
    }

    private static void assertPromotion(int width, int height, ScreenAnchorPromotion.Axis axis,
                                        int remainingWidth, int remainingHeight) {
        assertEquals(new ScreenAnchorPromotion.Result(axis, remainingWidth, remainingHeight),
                ScreenAnchorPromotion.choose(width, height));
    }
}
