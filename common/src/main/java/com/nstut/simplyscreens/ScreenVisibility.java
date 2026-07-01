package com.nstut.simplyscreens;

/** Version-neutral visibility checks for a screen structure's full block bounds. */
public final class ScreenVisibility {
    private ScreenVisibility() {
    }

    public static boolean isWithinDistance(double cameraX, double cameraY, double cameraZ,
                                           int anchorX, int anchorY, int anchorZ,
                                           int farX, int farY, int farZ,
                                           double viewDistance) {
        double minX = Math.min(anchorX, farX);
        double minY = Math.min(anchorY, farY);
        double minZ = Math.min(anchorZ, farZ);
        double maxX = Math.max(anchorX, farX) + 1.0;
        double maxY = Math.max(anchorY, farY) + 1.0;
        double maxZ = Math.max(anchorZ, farZ) + 1.0;

        double closestX = clamp(cameraX, minX, maxX);
        double closestY = clamp(cameraY, minY, maxY);
        double closestZ = clamp(cameraZ, minZ, maxZ);
        double dx = cameraX - closestX;
        double dy = cameraY - closestY;
        double dz = cameraZ - closestZ;
        return dx * dx + dy * dy + dz * dz <= viewDistance * viewDistance;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
