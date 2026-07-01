package com.nstut.simplyscreens;

/** Selects one screen cell to render when the real anchor is unavailable. */
public final class ScreenRenderProxy {
    private ScreenRenderProxy() {
    }

    public static Position nearestCell(double cameraX, double cameraY, double cameraZ,
                                       int anchorX, int anchorY, int anchorZ,
                                       int widthX, int widthY, int widthZ,
                                       int heightX, int heightY, int heightZ,
                                       int width, int height) {
        // Project the camera onto the screen's two local axes, then clamp to an actual screen cell.
        double fromCenterX = cameraX - anchorX - 0.5;
        double fromCenterY = cameraY - anchorY - 0.5;
        double fromCenterZ = cameraZ - anchorZ - 0.5;
        int widthIndex = clamp((int) Math.round(fromCenterX * widthX + fromCenterY * widthY + fromCenterZ * widthZ), width);
        int heightIndex = clamp((int) Math.round(fromCenterX * heightX + fromCenterY * heightY + fromCenterZ * heightZ), height);
        return new Position(
                anchorX + widthIndex * widthX + heightIndex * heightX,
                anchorY + widthIndex * widthY + heightIndex * heightY,
                anchorZ + widthIndex * widthZ + heightIndex * heightZ);
    }

    private static int clamp(int index, int size) {
        return Math.max(0, Math.min(Math.max(1, size) - 1, index));
    }

    public record Position(int x, int y, int z) {
    }
}
