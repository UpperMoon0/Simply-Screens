package com.nstut.simplyscreens.client.renderers;

final class LogicalScreenOwnership {
    private LogicalScreenOwnership() {
    }

    static boolean canOwn(boolean anchorEntityLoaded, int anchorOffsetX, int anchorOffsetY, int anchorOffsetZ) {
        return !anchorEntityLoaded
                || (anchorOffsetX == 0 && anchorOffsetY == 0 && anchorOffsetZ == 0);
    }
}
