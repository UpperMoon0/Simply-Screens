package com.nstut.simplyscreens.playlist;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A saved list. Replacing it never edits a running playback snapshot. */
public record NamedPlaylist(UUID id, String name, List<PlaylistSlide> slides) {
    public static final int MAX_SLIDES = 256;
    public static final int MAX_NAME_LENGTH = 64;

    public NamedPlaylist {
        Objects.requireNonNull(id, "Missing playlist ID");
        if (!validName(name)) throw new IllegalArgumentException("Invalid playlist name");
        slides = List.copyOf(slides);
        if (slides.size() > MAX_SLIDES) throw new IllegalArgumentException("Too many slides");
    }

    public static boolean validName(String name) {
        return name != null && !name.isBlank() && name.equals(name.strip())
                && name.length() <= MAX_NAME_LENGTH && name.codePoints().noneMatch(Character::isISOControl);
    }
}
