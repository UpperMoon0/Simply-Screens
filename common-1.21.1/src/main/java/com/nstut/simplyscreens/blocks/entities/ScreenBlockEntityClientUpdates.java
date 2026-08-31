package com.nstut.simplyscreens.blocks.entities;

import java.util.Objects;

/**
 * Sided-neutral bridge for reacting to client block-entity NBT updates.
 * The loader's client initializer installs the handler, keeping this package
 * free of references to Minecraft client-only classes.
 */
public final class ScreenBlockEntityClientUpdates {
    private static volatile Listener listener = screen -> {};

    private ScreenBlockEntityClientUpdates() {}

    public static void register(Listener listener) {
        ScreenBlockEntityClientUpdates.listener = Objects.requireNonNull(listener, "listener");
    }

    static void notifyUpdated(ScreenBlockEntity screen) {
        listener.onUpdated(screen);
    }

    @FunctionalInterface
    public interface Listener {
        void onUpdated(ScreenBlockEntity screen);
    }
}
