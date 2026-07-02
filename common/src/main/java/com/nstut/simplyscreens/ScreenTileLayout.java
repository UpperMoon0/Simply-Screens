package com.nstut.simplyscreens;

/** Calculates the visible image slice contributed by one block in a screen structure. */
public final class ScreenTileLayout {
    private ScreenTileLayout() {
    }

    public static Tile calculate(int width, int height, int widthIndex, int heightIndex,
                                 float imageWidth, float imageHeight) {
        float cellCenterX = (width - 1) / 2f - widthIndex;
        float cellCenterY = heightIndex - (height - 1) / 2f;
        float minX = Math.max(cellCenterX - 0.5f, -imageWidth / 2f);
        float maxX = Math.min(cellCenterX + 0.5f, imageWidth / 2f);
        float minY = Math.max(cellCenterY - 0.5f, -imageHeight / 2f);
        float maxY = Math.min(cellCenterY + 0.5f, imageHeight / 2f);
        if (minX >= maxX || minY >= maxY || imageWidth <= 0 || imageHeight <= 0) return Tile.EMPTY;

        return new Tile(minX, maxX, minY, maxY,
                0.5f - maxX / imageWidth, 0.5f - minX / imageWidth,
                0.5f - maxY / imageHeight, 0.5f - minY / imageHeight);
    }

    public record Tile(float minX, float maxX, float minY, float maxY,
                       float minU, float maxU, float minV, float maxV) {
        public static final Tile EMPTY = new Tile(0, 0, 0, 0, 0, 0, 0, 0);

        public boolean isEmpty() {
            return minX >= maxX || minY >= maxY;
        }

        public float area() {
            return Math.max(0, maxX - minX) * Math.max(0, maxY - minY);
        }
    }
}
