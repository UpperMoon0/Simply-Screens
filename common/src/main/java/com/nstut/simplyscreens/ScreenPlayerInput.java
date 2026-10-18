package com.nstut.simplyscreens;
import java.util.UUID;
import java.util.function.Function;
/** Resolve names from the online roster only; never perform a blocking profile lookup. */
public final class ScreenPlayerInput {
    private ScreenPlayerInput() { }
    public static UUID resolve(String input, Function<String, UUID> onlinePlayer) {
        if (input == null) return null;
        String value = input.trim();
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException ignored) {
            return value.matches("[A-Za-z0-9_]{1,16}") ? onlinePlayer.apply(value) : null;
        }
    }
}
