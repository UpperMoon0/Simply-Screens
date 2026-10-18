package com.nstut.simplyscreens.playlist;

import com.google.gson.Gson;
import java.util.*;

/** Bounded editor messages. Libraries are summarized; only one saved playlist is expanded. */
public final class PlaylistWire {
    public static final int REQUEST_LIMIT = 1024, RESPONSE_LIMIT = 65536;
    private static final Gson JSON = new Gson();
    private PlaylistWire() { }
    public record Request(String key, long networkRevision, long libraryRevision, long playbackRevision,
                          long requestId, String action, UUID playlist, UUID image, int index, int destination,
                          long durationMillis, String value) {
        public Request {
            if (key == null || key.length() > 128 || action == null || action.length() > 32
                    || value == null || value.length() > 64 || requestId <= 0
                    || networkRevision < 0 || libraryRevision < 0 || playbackRevision < 0)
                throw new IllegalArgumentException("Invalid playlist request");
        }
        public String json() { return JSON.toJson(this); }
        public static Request parse(String json) {
            if (json == null || json.length() > REQUEST_LIMIT) throw new IllegalArgumentException("Oversized request");
            return Objects.requireNonNull(JSON.fromJson(json, Request.class));
        }
    }
    public record Summary(UUID id, String name, int count) {
        public Summary { if (id == null || !NamedPlaylist.validName(name) || count < 0 || count > 256) throw new IllegalArgumentException("Invalid playlist summary"); }
    }
    public record Slide(UUID image, long durationMillis) {
        public Slide { new PlaylistSlide(image, durationMillis); }
    }
    public record View(String key, long networkRevision, long libraryRevision, long playbackRevision,
                       long requestId, boolean success, boolean control, UUID selected,
                       List<Summary> playlists, List<Slide> slides, String status, UUID currentImage,
                       int index, long offsetMillis, String repeat, boolean shuffle, List<Slide> queue,
                       List<UUID> upcoming) {
        public View {
            playlists = List.copyOf(playlists); slides = List.copyOf(slides);
            queue = List.copyOf(queue); upcoming = List.copyOf(upcoming);
            if (key == null || key.length() > 128 || networkRevision < 0 || libraryRevision < 0 || playbackRevision < 0
                    || requestId <= 0 || index < -1 || index >= 256 || offsetMillis < 0
                    || !Set.of("STOPPED", "PLAYING", "PAUSED", "ANNOUNCEMENT").contains(status)
                    || !Set.of("NONE", "CURRENT", "LIST").contains(repeat)) throw new IllegalArgumentException("Invalid playlist view");
            if (playlists.size() > 64 || slides.size() > 256 || queue.size() > 64 || upcoming.size() > 2)
                throw new IllegalArgumentException("Oversized playlist view");
        }
        public String json() { return JSON.toJson(this); }
        public static View parse(String json) {
            if (json == null || json.length() > RESPONSE_LIMIT) throw new IllegalArgumentException("Oversized view");
            return Objects.requireNonNull(JSON.fromJson(json, View.class));
        }
    }
}
