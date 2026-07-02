package com.nstut.simplyscreens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenTileLayoutTest {
    @Test
    void tilesReconstructStretchedImageWithoutOverlap() {
        assertAggregateArea(6, 5, 6, 5);
        assertAggregateArea(32, 18, 32, 18);
    }

    @Test
    void tilesReconstructLetterboxedImageWithoutOverlap() {
        assertAggregateArea(6, 5, 6, 3.375f);
        assertAggregateArea(5, 6, 3.375f, 6);
    }

    @Test
    void tileUvCoordinatesMatchItsImagePosition() {
        ScreenTileLayout.Tile tile = ScreenTileLayout.calculate(2, 1, 0, 0, 2, 1);
        assertEquals(0.0f, tile.minU(), 0.0001f);
        assertEquals(0.5f, tile.maxU(), 0.0001f);
        assertEquals(0.0f, tile.minV(), 0.0001f);
        assertEquals(1.0f, tile.maxV(), 0.0001f);
    }

    @Test
    void everyBlockContributesExactlyItsCellWhenStretched() {
        for (int width = 1; width <= 20; width++) {
            for (int height = 1; height <= 20; height++) {
                for (int w = 0; w < width; w++) {
                    for (int h = 0; h < height; h++) {
                        ScreenTileLayout.Tile tile = ScreenTileLayout.calculate(width, height, w, h, width, height);
                        assertEquals(1f, tile.area(), 0.0001f);
                        assertValidTile(tile);
                    }
                }
            }
        }
    }

    @Test
    void reconstructsCommonAspectRatiosAcrossScreenSizes() {
        float[] aspects = {0.25f, 0.5f, 1f, 4f / 3f, 16f / 9f, 21f / 9f, 4f};
        for (int width = 1; width <= 20; width++) {
            for (int height = 1; height <= 20; height++) {
                for (float aspect : aspects) {
                    float screenAspect = (float) width / height;
                    float imageWidth = aspect > screenAspect ? width : height * aspect;
                    float imageHeight = aspect > screenAspect ? width / aspect : height;
                    assertAggregateArea(width, height, imageWidth, imageHeight);
                    assertAllTilesValid(width, height, imageWidth, imageHeight);
                }
            }
        }
    }

    private static void assertAggregateArea(int width, int height, float imageWidth, float imageHeight) {
        assertEquals(imageWidth * imageHeight, aggregateArea(width, height, imageWidth, imageHeight), 0.001f);
    }

    private static float aggregateArea(int width, int height, float imageWidth, float imageHeight) {
        float area = 0;
        int nonEmptyTiles = 0;
        for (int w = 0; w < width; w++) {
            for (int h = 0; h < height; h++) {
                ScreenTileLayout.Tile tile = ScreenTileLayout.calculate(width, height, w, h, imageWidth, imageHeight);
                area += tile.area();
                if (!tile.isEmpty()) nonEmptyTiles++;
            }
        }
        assertTrue(nonEmptyTiles > 0);
        return area;
    }

    private static void assertAllTilesValid(int width, int height, float imageWidth, float imageHeight) {
        for (int w = 0; w < width; w++) {
            for (int h = 0; h < height; h++) {
                ScreenTileLayout.Tile tile = ScreenTileLayout.calculate(width, height, w, h, imageWidth, imageHeight);
                if (!tile.isEmpty()) assertValidTile(tile);
            }
        }
    }

    private static void assertValidTile(ScreenTileLayout.Tile tile) {
        assertTrue(tile.minX() < tile.maxX());
        assertTrue(tile.minY() < tile.maxY());
        assertTrue(tile.minU() >= 0 && tile.maxU() <= 1 && tile.minU() < tile.maxU());
        assertTrue(tile.minV() >= 0 && tile.maxV() <= 1 && tile.minV() < tile.maxV());
    }
}
