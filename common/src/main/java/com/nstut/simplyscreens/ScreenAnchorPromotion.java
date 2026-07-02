package com.nstut.simplyscreens;

/** Chooses the adjacent replacement anchor that preserves the largest screen rectangle. */
public final class ScreenAnchorPromotion {
    private ScreenAnchorPromotion() {
    }

    public static Result choose(int width, int height) {
        if (width <= 1 && height <= 1) return new Result(Axis.NONE, 1, 1);
        int areaAfterWidthPromotion = Math.max(0, width - 1) * height;
        int areaAfterHeightPromotion = width * Math.max(0, height - 1);
        if (areaAfterHeightPromotion > areaAfterWidthPromotion) {
            return new Result(Axis.HEIGHT, width, height - 1);
        }
        return new Result(Axis.WIDTH, width - 1, height);
    }

    public enum Axis {
        NONE,
        WIDTH,
        HEIGHT
    }

    public record Result(Axis axis, int width, int height) {
    }
}
