package com.nstut.simplyscreens.playlist;

/** Measures running server ticks so menus, debugger stops and suspension cannot consume a slideshow. */
public final class PlaybackTickClock {
    private boolean started;
    private int previousTick;

    public long advance(int tick) {
        long elapsed = started ? Integer.toUnsignedLong(tick - previousTick) * 50L : 0;
        previousTick = tick;
        started = true;
        return elapsed;
    }

    public void reset() { started = false; }
}
