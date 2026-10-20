package com.nstut.simplyscreens.playlist;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Immutable personal library. Its revision protects every edit, including create/delete. */
public record PlaylistLibrarySnapshot(long revision, List<NamedPlaylist> playlists) {
    public static final int MAX_PLAYLISTS = 64;
    public static final PlaylistLibrarySnapshot EMPTY = new PlaylistLibrarySnapshot(0, List.of());

    public PlaylistLibrarySnapshot {
        if (revision < 0) throw new IllegalArgumentException("Invalid library revision");
        playlists = List.copyOf(playlists);
        if (playlists.size() > MAX_PLAYLISTS) throw new IllegalArgumentException("Too many playlists");
        Set<UUID> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (NamedPlaylist playlist : playlists) {
            if (!ids.add(playlist.id()) || !names.add(playlist.name().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Duplicate playlist identity or name");
        }
    }

    public NamedPlaylist find(UUID id) {
        return playlists.stream().filter(playlist -> playlist.id().equals(id)).findFirst().orElse(null);
    }
}
