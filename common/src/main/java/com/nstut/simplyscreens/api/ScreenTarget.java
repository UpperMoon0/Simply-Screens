package com.nstut.simplyscreens.api;

import com.nstut.simplyscreens.ScreenNetworkService;
import java.util.UUID;

/** Parses a typed persistent target key without creating or loading a screen. */
public final class ScreenTarget {
    private ScreenTarget() { }

    /** Accepts a plain Screen ID, network/Screen ID, or display/UUID. */
    public static String parse(String input) {
        if (input == null) throw new IllegalArgumentException("Missing screen target");
        if (input.startsWith("display/"))
            return ScreenNetworkService.display(UUID.fromString(input.substring("display/".length())));
        if (input.startsWith("network/"))
            return ScreenNetworkService.network(input.substring("network/".length()));
        return ScreenNetworkService.network(input);
    }
}
