package com.nstut.simplyscreens.testing;

/** A native join probe may connect once, after ordinary startup and account readiness. */
public final class NativeMultiplayerJoinGate {
    private boolean started;
    public boolean claim(boolean titleScreen, boolean loadingOverlay, boolean multiplayerAllowed) {
        if (started || !titleScreen || loadingOverlay || !multiplayerAllowed) return false;
        started = true;
        return true;
    }
}
