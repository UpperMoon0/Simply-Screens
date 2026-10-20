package com.nstut.simplyscreens.playlist;

import java.util.Objects;
import java.util.UUID;

/** Immutable image reference and server-tick display duration. */
public record PlaylistSlide(UUID image, long durationMillis) {
    public static final long MIN_DURATION_MILLIS = 1_000;
    public static final long MAX_DURATION_MILLIS = 86_400_000;

    public PlaylistSlide {
        Objects.requireNonNull(image, "Missing image");
        if (durationMillis < MIN_DURATION_MILLIS || durationMillis > MAX_DURATION_MILLIS)
            throw new IllegalArgumentException("Slide duration must be between one second and one day");
    }
}
