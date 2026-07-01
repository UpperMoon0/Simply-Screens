package com.nstut.simplyscreens;

import java.util.function.BiPredicate;

/** Finds the largest complete rectangle extending from a screen anchor. */
public final class ScreenStructureDetector {
    private ScreenStructureDetector() {
    }

    public static Bounds detect(int maxWidth, int maxHeight, BiPredicate<Integer, Integer> occupied) {
        if (maxWidth < 0 || maxHeight < 0) {
            throw new IllegalArgumentException("Structure extents must not be negative");
        }

        int bestWidth = 0;
        int bestHeight = 0;
        int shortestRowWidth = maxWidth;

        for (int height = 0; height <= maxHeight; height++) {
            int rowWidth = 0;
            while (rowWidth <= shortestRowWidth && (rowWidth == 0 && height == 0 || occupied.test(rowWidth, height))) {
                rowWidth++;
            }
            shortestRowWidth = Math.min(shortestRowWidth, rowWidth - 1);

            int area = (shortestRowWidth + 1) * (height + 1);
            int bestArea = (bestWidth + 1) * (bestHeight + 1);
            if (area > bestArea || area == bestArea && shortestRowWidth < bestWidth) {
                bestWidth = shortestRowWidth;
                bestHeight = height;
            }
        }

        return new Bounds(bestWidth, bestHeight);
    }

    public record Bounds(int width, int height) {
    }
}
