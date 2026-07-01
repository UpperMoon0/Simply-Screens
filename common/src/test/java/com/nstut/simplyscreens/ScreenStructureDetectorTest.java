package com.nstut.simplyscreens;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScreenStructureDetectorTest {
    @Test
    void formsAndGrowsFromSingleScreen() {
        Structure structure = new Structure();
        assertBounds(1, 1, structure);

        structure.place(1, 0);
        assertBounds(2, 1, structure);

        structure.place(0, 1).place(1, 1);
        assertBounds(2, 2, structure);

        structure.place(2, 0).place(2, 1);
        assertBounds(3, 2, structure);

        structure.place(0, 2).place(1, 2).place(2, 2);
        assertBounds(3, 3, structure);
    }

    @Test
    void shrinksAndRestoresAfterEdgeBreaks() {
        Structure structure = Structure.rectangle(4, 3);
        assertBounds(4, 3, structure);

        structure.breakAt(3, 0).breakAt(3, 1).breakAt(3, 2);
        assertBounds(3, 3, structure);

        structure.place(3, 0).place(3, 1).place(3, 2);
        assertBounds(4, 3, structure);

        structure.breakAt(0, 2).breakAt(1, 2).breakAt(2, 2).breakAt(3, 2);
        assertBounds(4, 2, structure);
    }

    @Test
    void choosesSameRectangleAfterCornerAndInteriorBreaks() {
        Structure structure = Structure.rectangle(3, 3);

        structure.breakAt(2, 2);
        assertBounds(2, 3, structure); // Equal-area ties prefer the narrower structure.

        structure.place(2, 2).breakAt(1, 1);
        assertBounds(1, 3, structure);
    }

    @Test
    void ignoresScreensDisconnectedFromAnchorAxes() {
        Structure structure = new Structure().place(2, 0).place(0, 2).place(2, 2);
        assertBounds(1, 1, structure);

        structure.place(1, 0).place(0, 1).place(1, 1).place(2, 1);
        assertBounds(3, 2, structure);
    }

    private static void assertBounds(int width, int height, Structure structure) {
        assertEquals(new ScreenStructureDetector.Bounds(width - 1, height - 1), structure.detect());
    }

    private static final class Structure {
        private final Set<Cell> screens = new HashSet<>();

        private Structure() {
            screens.add(new Cell(0, 0));
        }

        static Structure rectangle(int width, int height) {
            Structure structure = new Structure();
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) structure.place(x, y);
            }
            return structure;
        }

        Structure place(int x, int y) {
            screens.add(new Cell(x, y));
            return this;
        }

        Structure breakAt(int x, int y) {
            screens.remove(new Cell(x, y));
            return this;
        }

        ScreenStructureDetector.Bounds detect() {
            int maxWidth = contiguousExtent(1, 0);
            int maxHeight = contiguousExtent(0, 1);
            return ScreenStructureDetector.detect(maxWidth, maxHeight,
                    (x, y) -> screens.contains(new Cell(x, y)));
        }

        private int contiguousExtent(int stepX, int stepY) {
            int extent = 0;
            while (screens.contains(new Cell((extent + 1) * stepX, (extent + 1) * stepY))) extent++;
            return extent;
        }
    }

    private record Cell(int x, int y) {
    }

}
