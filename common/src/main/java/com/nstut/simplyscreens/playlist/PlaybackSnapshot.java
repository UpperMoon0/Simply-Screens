package com.nstut.simplyscreens.playlist;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable view. Queue and saved-snapshot slides never expose mutable server collections. */
public record PlaybackSnapshot(Status status, UUID sourceOwner, UUID sourceId, List<PlaylistSlide> slides,
                               int slideIndex, long offsetMillis, RepeatMode repeat, boolean shuffle,
                               List<PlaylistSlide> queue, boolean queued, boolean announcing,
                               PlaylistSlide current, List<UUID> upcomingImages, long revision) {
    public enum Status { STOPPED, PLAYING, PAUSED }
    public static final PlaybackSnapshot EMPTY = new PlaybackSnapshot(Status.STOPPED, null, null, List.of(),
            -1, 0, RepeatMode.NONE, false, List.of(), false, false, null, List.of(), 0);

    public PlaybackSnapshot {
        Objects.requireNonNull(status);
        Objects.requireNonNull(repeat);
        slides = List.copyOf(slides);
        queue = List.copyOf(queue);
        upcomingImages = List.copyOf(upcomingImages);
        if (slides.size() > NamedPlaylist.MAX_SLIDES || queue.size() > ScreenPlaylistService.MAX_QUEUE
                || upcomingImages.size() > 2 || slideIndex < -1 || slideIndex >= slides.size()
                || offsetMillis < 0 || revision < 0 || current != null && offsetMillis >= current.durationMillis()
                || status == Status.STOPPED && current != null || status != Status.STOPPED && current == null)
            throw new IllegalArgumentException("Invalid playback snapshot");
    }

    public UUID currentImage() { return current == null ? null : current.image(); }
    public boolean playing() { return status == Status.PLAYING; }
    public long durationMillis() { return current == null ? 0 : current.durationMillis(); }
    public int slideCount() { return slides.size(); }
}
