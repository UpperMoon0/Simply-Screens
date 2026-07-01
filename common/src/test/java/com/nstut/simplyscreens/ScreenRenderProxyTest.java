package com.nstut.simplyscreens;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScreenRenderProxyTest {
    @Test
    void selectsNearestCellAndClampsToScreenEdges() {
        assertEquals(new ScreenRenderProxy.Position(9, 4, 0), ScreenRenderProxy.nearestCell(
                20, 20, 5, 0, 0, 0, 1, 0, 0, 0, 1, 0, 10, 5));
    }

    @Test
    void supportsNegativeFacingAxes() {
        assertEquals(new ScreenRenderProxy.Position(6, 5, 10), ScreenRenderProxy.nearestCell(
                5, 5, 10.5, 10, 5, 10, -1, 0, 0, 0, 0, -1, 5, 4));
    }
}
