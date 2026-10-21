package com.nstut.simplyscreens.api;

import com.nstut.simplyscreens.playlist.PlaybackSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Server-side lifecycle notifications. Mutations enqueue immutable events only after persistence
 * succeeds. Delivery happens at the end of the server tick, outside persistence locks.
 * Subscribers must not hold references to world objects; registration survives world reloads.
 */
public final class ScreenEvents {
    public enum Type {
        IMAGE_CHANGED, SLIDESHOW_STARTED, SLIDESHOW_PAUSED, SLIDESHOW_RESUMED,
        SLIDESHOW_STOPPED, SLIDE_ADVANCED, SLIDESHOW_COMPLETED
    }

    /** The image is the currently displayed asset, never a private unused library entry. */
    public record Change(Type type, String key, UUID image, int slideIndex, long revision) {
        public Change {
            Objects.requireNonNull(type);
            Objects.requireNonNull(key);
        }
    }

    private static final int MAX_PENDING = 16384;
    private static final CopyOnWriteArrayList<Consumer<Change>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ArrayDeque<Change> PENDING = new ArrayDeque<>();
    private ScreenEvents() { }

    /** Returns a disposable registration; listeners are invoked on the Minecraft server thread. */
    public static AutoCloseable listen(Consumer<Change> listener) {
        Objects.requireNonNull(listener);
        LISTENERS.addIfAbsent(listener);
        return () -> LISTENERS.remove(listener);
    }

    public static void removeListener(Consumer<Change> listener) {
        LISTENERS.remove(listener);
    }

    public static synchronized void enqueue(Change change) {
        if (PENDING.size() >= MAX_PENDING) PENDING.removeFirst();
        PENDING.addLast(Objects.requireNonNull(change));
    }

    public static void imageChanged(String key, UUID image, long revision) {
        enqueue(new Change(Type.IMAGE_CHANGED, key, image, -1, revision));
    }

    /** Explicit play replaces the running snapshot even when its source identity is unchanged. */
    public static void playbackStarted(String key, PlaybackSnapshot playback) {
        enqueue(new Change(Type.SLIDESHOW_STARTED, key, playback.currentImage(), playback.slideIndex(), playback.revision()));
    }

    /** Compare logical sessions, never individual blocks linked to a network. */
    public static void playbackChanged(String key, PlaybackSnapshot before,
                                       PlaybackSnapshot after, boolean automaticAdvance) {
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        var oldStatus = before.status();
        var newStatus = after.status();
        if (newStatus == PlaybackSnapshot.Status.PLAYING) {
            if (oldStatus == PlaybackSnapshot.Status.STOPPED
                    || !Objects.equals(before.sourceId(), after.sourceId()))
                enqueue(new Change(Type.SLIDESHOW_STARTED, key, after.currentImage(), after.slideIndex(), after.revision()));
            else if (oldStatus == PlaybackSnapshot.Status.PAUSED)
                enqueue(new Change(Type.SLIDESHOW_RESUMED, key, after.currentImage(), after.slideIndex(), after.revision()));
        } else if (newStatus == PlaybackSnapshot.Status.PAUSED && oldStatus == PlaybackSnapshot.Status.PLAYING) {
            enqueue(new Change(Type.SLIDESHOW_PAUSED, key, after.currentImage(), after.slideIndex(), after.revision()));
        } else if (newStatus == PlaybackSnapshot.Status.STOPPED && oldStatus != PlaybackSnapshot.Status.STOPPED) {
            enqueue(new Change(Type.SLIDESHOW_STOPPED, key, null, -1, after.revision()));
            if (automaticAdvance)
                enqueue(new Change(Type.SLIDESHOW_COMPLETED, key, null, -1, after.revision()));
        }
        if (oldStatus != PlaybackSnapshot.Status.STOPPED
                && newStatus != PlaybackSnapshot.Status.STOPPED
                && Objects.equals(before.sourceId(), after.sourceId())
                && (before.slideIndex() != after.slideIndex()
                    || !Objects.equals(before.currentImage(), after.currentImage())))
            enqueue(new Change(Type.SLIDE_ADVANCED, key, after.currentImage(), after.slideIndex(), after.revision()));
    }

    public static synchronized void resetPending() {
        PENDING.clear();
    }

    /** Drain a bounded batch; subscriber errors cannot interrupt simulation or later listeners. */
    public static void flush(Consumer<Throwable> errorReporter) {
        List<Change> batch = new ArrayList<>();
        synchronized (ScreenEvents.class) {
            while (!PENDING.isEmpty() && batch.size() < MAX_PENDING) batch.add(PENDING.removeFirst());
        }
        for (Change change : batch) for (Consumer<Change> listener : LISTENERS) {
            try { listener.accept(change); }
            catch (Throwable failure) { if (errorReporter != null) errorReporter.accept(failure); }
        }
    }
}
