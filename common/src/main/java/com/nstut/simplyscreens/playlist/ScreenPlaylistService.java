package com.nstut.simplyscreens.playlist;

import com.nstut.simplyscreens.api.ScreenEvents;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.CRC32;

import static com.nstut.simplyscreens.playlist.PlaybackSnapshot.Status;

/**
 * World-scoped saved libraries and server-authoritative playback timelines.
 * Network permissions and image visibility must be checked by the native service before calling
 * playback mutations. Library mutations can only address the authenticated actor's own library.
 * Tick deltas must come from the monotonic server tick clock, never wall-clock timestamps.
 */
public final class ScreenPlaylistService {
    public static final int MAX_QUEUE = 64;
    public static final int MAX_NETWORKS = 1024;
    public static final int MAX_OWNERS = 4096;
    public static final int MAX_STORE_BYTES = 64 * 1024 * 1024;
    public static final String FILE_NAME = "screen_playlists.dat";
    private static final int MAGIC = 0x5353504C;
    private static final int VERSION = 2;
    private final Map<UUID, PlaylistLibrarySnapshot> libraries = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private Path file;
    private boolean healthy = true;
    private boolean dirty;

    /** Internal mutable state is always copied before a transactional command. Never exposed. */
    private static final class Session {
        UUID sourceOwner;
        UUID sourceId;
        List<PlaylistSlide> slides = List.of();
        List<Integer> order = List.of();
        int cursor = -1;
        long offset;
        Status status = Status.STOPPED;
        RepeatMode repeat = RepeatMode.NONE;
        boolean shuffle;
        long shuffleSeed;
        List<PlaylistSlide> queue = new ArrayList<>();
        PlaylistSlide queuedSlide;
        boolean advanceCanonicalOnce;
        boolean announcement;
        Session suspended;
        long revision;
        boolean ownsContent;

        Session copy() {
            Session result = new Session();
            result.ownsContent = ownsContent;
            result.sourceOwner = sourceOwner; result.sourceId = sourceId;
            result.slides = slides; result.order = order; result.cursor = cursor;
            result.offset = offset; result.status = status; result.repeat = repeat;
            result.shuffle = shuffle; result.shuffleSeed = shuffleSeed;
            result.queue = new ArrayList<>(queue); result.queuedSlide = queuedSlide;
            result.advanceCanonicalOnce = advanceCanonicalOnce;
            result.announcement = announcement;
            result.suspended = suspended == null ? null : suspended.copy(); result.revision = revision;
            return result;
        }
        Session base() { return announcement ? suspended : this; }
        PlaylistSlide current() {
            if (status == Status.STOPPED) return null;
            if (queuedSlide != null) return queuedSlide;
            return cursor >= 0 && cursor < order.size() ? slides.get(order.get(cursor)) : null;
        }
        int slideIndex() { return cursor >= 0 && cursor < order.size() ? order.get(cursor) : -1; }
        void restore() {
            Session saved = suspended;
            long latestRevision = revision;
            // Restoring a displayed announcement also owns the resulting clear/content write.
            ownsContent = true;
            sourceOwner = saved.sourceOwner; sourceId = saved.sourceId; slides = saved.slides;
            order = saved.order; cursor = saved.cursor; offset = saved.offset; status = saved.status;
            repeat = saved.repeat; shuffle = saved.shuffle; shuffleSeed = saved.shuffleSeed;
            queue = new ArrayList<>(saved.queue); queuedSlide = saved.queuedSlide;
            advanceCanonicalOnce = saved.advanceCanonicalOnce;
            announcement = false; suspended = null; revision = latestRevision;
        }
        void stop() {
            if (announcement) restore();
            ownsContent = true;
            status = Status.STOPPED; offset = 0; cursor = -1; queuedSlide = null; queue.clear(); advanceCanonicalOnce = false;
        }
        /** Queue entries play once, then continue after the retained canonical cursor. */
        void advance(boolean explicit) {
            offset = 0;
            if (announcement) { restore(); return; }
            if (!queue.isEmpty()) {
                ownsContent = true;
                queuedSlide = queue.remove(0); status = Status.PLAYING; return;
            }
            queuedSlide = null;
            explicit |= advanceCanonicalOnce; advanceCanonicalOnce = false;
            if (order.isEmpty()) { status = Status.STOPPED; cursor = -1; return; }
            int next = !explicit && repeat == RepeatMode.CURRENT && cursor >= 0 ? cursor : cursor + 1;
            if (next >= order.size()) {
                if (repeat == RepeatMode.LIST) next = 0;
                else { status = Status.STOPPED; cursor = -1; return; }
            }
            ownsContent = true;
            cursor = next; status = Status.PLAYING;
        }
        void reorder(boolean enabled, long seed) {
            int selected = slideIndex();
            shuffle = enabled; shuffleSeed = seed;
            List<Integer> replacement = new ArrayList<>();
            for (int i = 0; i < slides.size(); i++) replacement.add(i);
            if (enabled) Collections.shuffle(replacement, new Random(seed));
            order = List.copyOf(replacement);
            cursor = selected < 0 ? -1 : order.indexOf(selected);
        }
    }

    public synchronized boolean healthy() { return healthy; }
    public synchronized boolean dirty() { return dirty; }
    public synchronized PlaylistLibrarySnapshot library(UUID actor) {
        if (actor == null || !healthy) return PlaylistLibrarySnapshot.EMPTY;
        return libraries.getOrDefault(actor, PlaylistLibrarySnapshot.EMPTY);
    }
    public synchronized PlaybackSnapshot snapshot(String key) {
        if (!healthy) return PlaybackSnapshot.EMPTY;
        Session session = sessions.get(key);
        return session == null ? PlaybackSnapshot.EMPTY : capture(session);
    }
    public synchronized Map<String, PlaybackSnapshot> snapshots() {
        if (!healthy) return Map.of();
        Map<String, PlaybackSnapshot> result = new HashMap<>();
        sessions.forEach((key, session) -> result.put(key, capture(session)));
        return Map.copyOf(result);
    }

    public synchronized boolean managesContent(String key) {
        Session session = sessions.get(key);
        return healthy && session != null && session.ownsContent;
    }

    /** Sessions that own display content, including a durable stopped/clear intent. */
    public synchronized Map<String, PlaybackSnapshot> reconciliationSnapshots() {
        if (!healthy) return Map.of();
        Map<String, PlaybackSnapshot> result = new HashMap<>();
        sessions.forEach((key, session) -> { if (session.ownsContent) result.put(key, capture(session)); });
        return Map.copyOf(result);
    }

    public synchronized UUID create(UUID actor, String name, long expectedLibraryRevision) {
        if (!validLibraryMutation(actor, expectedLibraryRevision) || !NamedPlaylist.validName(name)) return null;
        PlaylistLibrarySnapshot old = library(actor);
        if (old.playlists().size() >= PlaylistLibrarySnapshot.MAX_PLAYLISTS || nameExists(old, name, null)) return null;
        UUID id = UUID.randomUUID();
        List<NamedPlaylist> next = new ArrayList<>(old.playlists());
        next.add(new NamedPlaylist(id, name, List.of()));
        commitLibrary(actor, new PlaylistLibrarySnapshot(increment(old.revision()), next));
        return id;
    }
    public synchronized boolean rename(UUID actor, UUID id, String name, long expectedLibraryRevision) {
        if (!validLibraryMutation(actor, expectedLibraryRevision) || !NamedPlaylist.validName(name)) return false;
        PlaylistLibrarySnapshot old = library(actor);
        NamedPlaylist playlist = old.find(id);
        if (playlist == null || nameExists(old, name, id)) return false;
        if (playlist.name().equals(name)) return true;
        return replace(actor, old, new NamedPlaylist(id, name, playlist.slides()));
    }
    public synchronized UUID duplicate(UUID actor, UUID id, String name, long expectedLibraryRevision) {
        if (!validLibraryMutation(actor, expectedLibraryRevision) || !NamedPlaylist.validName(name)) return null;
        PlaylistLibrarySnapshot old = library(actor);
        NamedPlaylist playlist = old.find(id);
        if (playlist == null || old.playlists().size() >= PlaylistLibrarySnapshot.MAX_PLAYLISTS
                || nameExists(old, name, null)) return null;
        UUID duplicateId = UUID.randomUUID();
        List<NamedPlaylist> next = new ArrayList<>(old.playlists());
        next.add(new NamedPlaylist(duplicateId, name, playlist.slides()));
        commitLibrary(actor, new PlaylistLibrarySnapshot(increment(old.revision()), next));
        return duplicateId;
    }
    public synchronized boolean delete(UUID actor, UUID id, long expectedLibraryRevision) {
        if (!validLibraryMutation(actor, expectedLibraryRevision)) return false;
        PlaylistLibrarySnapshot old = library(actor);
        if (old.find(id) == null) return false;
        List<NamedPlaylist> next = old.playlists().stream().filter(p -> !p.id().equals(id)).toList();
        commitLibrary(actor, new PlaylistLibrarySnapshot(increment(old.revision()), next));
        return true;
    }
    public synchronized boolean replaceSlides(UUID actor, UUID id, List<PlaylistSlide> slides, long expectedLibraryRevision) {
        if (!validLibraryMutation(actor, expectedLibraryRevision) || slides == null
                || slides.size() > NamedPlaylist.MAX_SLIDES || slides.stream().anyMatch(Objects::isNull)) return false;
        PlaylistLibrarySnapshot old = library(actor);
        NamedPlaylist playlist = old.find(id);
        if (playlist == null) return false;
        if (playlist.slides().equals(slides)) return true;
        return replace(actor, old, new NamedPlaylist(id, playlist.name(), slides));
    }
    private boolean replace(UUID actor, PlaylistLibrarySnapshot old, NamedPlaylist replacement) {
        List<NamedPlaylist> next = old.playlists().stream()
                .map(p -> p.id().equals(replacement.id()) ? replacement : p).toList();
        commitLibrary(actor, new PlaylistLibrarySnapshot(increment(old.revision()), next));
        return true;
    }
    private boolean validLibraryMutation(UUID actor, long expectedRevision) {
        return healthy && actor != null && expectedRevision >= 0 && library(actor).revision() == expectedRevision
                && (libraries.containsKey(actor) || libraries.size() < MAX_OWNERS);
    }
    private static boolean nameExists(PlaylistLibrarySnapshot library, String name, UUID except) {
        return library.playlists().stream().anyMatch(p -> !p.id().equals(except) && p.name().equalsIgnoreCase(name));
    }
    private void commitLibrary(UUID actor, PlaylistLibrarySnapshot next) {
        PlaylistLibrarySnapshot old = libraries.put(actor, next);
        boolean wasDirty = dirty; dirty = true;
        try { save(); } catch (RuntimeException failure) {
            if (old == null) libraries.remove(actor); else libraries.put(actor, old);
            dirty = wasDirty; throw failure;
        }
    }

    /** Creates an independent immutable image/duration snapshot from the owner's saved list. */
    public synchronized boolean play(String key, UUID libraryOwner, UUID playlistId, long expectedPlaybackRevision) {
        if (!validPlaybackMutation(key, expectedPlaybackRevision)) return false;
        NamedPlaylist playlist = library(libraryOwner).find(playlistId);
        if (playlist == null || playlist.slides().isEmpty()) return false;
        Session old = sessions.getOrDefault(key, new Session());
        Session next = new Session();
        next.ownsContent = true;
        next.sourceOwner = libraryOwner; next.sourceId = playlistId; next.slides = playlist.slides();
        next.repeat = old.base().repeat;
        next.reorder(old.base().shuffle, new Random().nextLong());
        next.cursor = 0; next.status = Status.PLAYING; next.revision = increment(old.revision);
        commitSession(key, next);
        return true;
    }
    public synchronized boolean command(String key, String action, long expectedRevision) {
        if (action == null || !validPlaybackMutation(key, expectedRevision)) return false;
        Session next = sessions.getOrDefault(key, new Session()).copy();
        switch (action) {
            case "PAUSE" -> {
                if (next.status != Status.PLAYING) return false;
                next.status = Status.PAUSED;
            }
            case "RESUME" -> {
                if (next.status == Status.PLAYING) return true;
                if (next.status == Status.STOPPED) { next.advance(true); if (next.current() == null) return false; }
                else next.status = Status.PLAYING;
            }
            case "STOP" -> next.stop();
            case "NEXT" -> {
                Status previous = next.status;
                boolean wasAnnouncement = next.announcement;
                next.advance(true);
                if (!wasAnnouncement && previous == Status.PAUSED && next.status == Status.PLAYING) next.status = Status.PAUSED;
            }
            case "PREVIOUS" -> {
                if (next.announcement) next.restore();
                else {
                    if (next.order.isEmpty()) return false;
                    if (next.queuedSlide != null) { next.queuedSlide = null; next.advanceCanonicalOnce = false; }
                    else if (next.cursor > 0) next.cursor--;
                    else next.cursor = next.repeat == RepeatMode.LIST ? next.order.size() - 1 : 0;
                    if (next.cursor < 0) next.cursor = 0;
                    next.offset = 0;
                    if (next.status == Status.STOPPED) next.status = Status.PLAYING;
                }
            }
            case "RESTART" -> {
                next.offset = 0;
                if (next.status == Status.STOPPED) { next.advance(true); if (next.current() == null) return false; }
            }
            case "CANCEL_ANNOUNCEMENT" -> { if (!next.announcement) return false; next.restore(); }
            default -> { return false; }
        }
        next.revision = increment(next.revision);
        commitSession(key, next); return true;
    }
    public synchronized boolean queue(String key, PlaylistSlide slide, boolean next, long expectedRevision) {
        if (slide == null || !validPlaybackMutation(key, expectedRevision)) return false;
        Session current = sessions.getOrDefault(key, new Session());
        if (current.base().queue.size() >= MAX_QUEUE) return false;
        return editSession(key, expectedRevision, state -> state.base().queue.add(next ? 0 : state.base().queue.size(), slide));
    }
    public synchronized boolean clearQueue(String key, long expectedRevision) {
        return editSession(key, expectedRevision, state -> state.base().queue.clear());
    }
    public synchronized boolean removeQueued(String key, int index, long expectedRevision) {
        if (!validPlaybackMutation(key, expectedRevision)) return false;
        Session current = sessions.getOrDefault(key, new Session());
        if (index < 0 || index >= current.base().queue.size()) return false;
        return editSession(key, expectedRevision, state -> state.base().queue.remove(index));
    }
    public synchronized boolean moveQueued(String key, int from, int to, long expectedRevision) {
        if (!validPlaybackMutation(key, expectedRevision)) return false;
        int size = sessions.getOrDefault(key, new Session()).base().queue.size();
        if (from < 0 || to < 0 || from >= size || to >= size) return false;
        return editSession(key, expectedRevision, state -> {
            List<PlaylistSlide> queue = state.base().queue; queue.add(to, queue.remove(from));
        });
    }
    public synchronized boolean announce(String key, PlaylistSlide slide, long expectedRevision) {
        if (slide == null || !validPlaybackMutation(key, expectedRevision)) return false;
        Session old = sessions.getOrDefault(key, new Session());
        Session next = new Session();
        next.suspended = old.announcement ? old.suspended.copy() : old.copy();
        next.ownsContent = true;
        next.announcement = true; next.queuedSlide = slide; next.status = Status.PLAYING;
        next.revision = increment(old.revision);
        commitSession(key, next); return true;
    }
    public synchronized boolean modes(String key, RepeatMode repeat, boolean shuffle, long expectedRevision) {
        if (repeat == null) return false;
        return editSession(key, expectedRevision, state -> {
            Session base = state.base(); base.repeat = repeat;
            if (base.shuffle != shuffle) base.reorder(shuffle, new Random().nextLong());
        });
    }
    private boolean editSession(String key, long expectedRevision, Consumer<Session> edit) {
        if (!validPlaybackMutation(key, expectedRevision)) return false;
        Session next = sessions.getOrDefault(key, new Session()).copy();
        edit.accept(next); next.revision = increment(next.revision);
        commitSession(key, next); return true;
    }
    private boolean validPlaybackMutation(String key, long expectedRevision) {
        if (!healthy || !validKey(key) || expectedRevision < 0) return false;
        Session current = sessions.get(key);
        return (current == null ? expectedRevision == 0 && sessions.size() < MAX_NETWORKS : current.revision == expectedRevision);
    }
    private void commitSession(String key, Session next) {
        validateSession(next, 0);
        Session old = sessions.put(key, next);
        PlaybackSnapshot before = old == null ? PlaybackSnapshot.EMPTY : capture(old);
        boolean wasDirty = dirty; dirty = true;
        try { save(); } catch (RuntimeException failure) {
            if (old == null) sessions.remove(key); else sessions.put(key, old);
            dirty = wasDirty; throw failure;
        }
        ScreenEvents.playbackChanged(key, before, capture(next), false);
    }

    /**
     * Advances all networks, including unloaded displays. Elapsed-only changes do not change the
     * control revision; a boundary transition advances it once for this tick. Downtime is not counted.
     */
    public synchronized Map<String, PlaybackSnapshot> tick(long elapsedMillis) {
        if (elapsedMillis < 0) throw new IllegalArgumentException("Negative tick delta");
        if (!healthy || elapsedMillis == 0) return Map.of();
        Map<String, PlaybackSnapshot> changed = new HashMap<>();
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (session.status != Status.PLAYING) continue;
            PlaybackSnapshot before = capture(session);
            boolean transition = advanceTime(session, elapsedMillis);
            dirty = true;
            if (transition) {
                session.revision = increment(session.revision);
                PlaybackSnapshot after = capture(session);
                changed.put(entry.getKey(), after);
                ScreenEvents.playbackChanged(entry.getKey(), before, after, true);
            }
        }
        return Map.copyOf(changed);
    }
    private static boolean advanceTime(Session session, long elapsed) {
        boolean transition = false;
        while (session.status == Status.PLAYING && elapsed > 0) {
            PlaylistSlide current = session.current();
            if (current == null) { session.stop(); return true; }
            long remaining = current.durationMillis() - session.offset;
            if (!session.announcement && session.queue.isEmpty() && session.queuedSlide == null) {
                if (session.repeat == RepeatMode.CURRENT) {
                    transition |= elapsed >= remaining;
                    session.offset = (session.offset + elapsed % current.durationMillis()) % current.durationMillis();
                    return transition;
                }
                if (session.repeat == RepeatMode.LIST && session.offset == 0) {
                    long cycle = session.slides.stream().mapToLong(PlaylistSlide::durationMillis).sum();
                    if (cycle > 0 && elapsed >= cycle) { transition = true; elapsed %= cycle; if (elapsed == 0) break; }
                }
            }
            if (elapsed < remaining) { session.offset += elapsed; break; }
            elapsed -= remaining; session.advance(false); transition = true;
        }
        return transition;
    }
    private static PlaybackSnapshot capture(Session session) {
        Session base = session.base();
        return new PlaybackSnapshot(session.status, base.sourceOwner, base.sourceId, base.slides,
                base.slideIndex(), session.offset, base.repeat, base.shuffle, base.queue,
                !session.announcement && session.queuedSlide != null, session.announcement,
                session.current(), upcoming(session), session.revision);
    }
    private static List<UUID> upcoming(Session session) {
        if (session.status == Status.STOPPED) return List.of();
        Session simulated = session.copy();
        simulated.status = Status.PLAYING;
        UUID current = session.current().image();
        List<UUID> result = new ArrayList<>();
        for (int n = 0; n < NamedPlaylist.MAX_SLIDES + MAX_QUEUE + 2 && result.size() < 2; n++) {
            simulated.advance(false);
            if (simulated.status == Status.STOPPED) break;
            PlaylistSlide next = simulated.current();
            if (next != null && !next.image().equals(current) && !result.contains(next.image())) result.add(next.image());
            if (simulated.status == Status.PAUSED) simulated.status = Status.PLAYING;
            if (!simulated.announcement && simulated.queue.isEmpty() && simulated.queuedSlide == null && simulated.repeat == RepeatMode.CURRENT) break;
        }
        return List.copyOf(result);
    }

    /** Deleting a saved playlist intentionally does not alter any running playback snapshot. */
    public synchronized void removeNetwork(String key) {
        removeNetworkIf(key, () -> true);
    }

    /** Persist cancellation before static selection; restore the session if selection fails. */
    public synchronized boolean removeNetworkIf(String key, java.util.function.BooleanSupplier selection) {
        if (!healthy) throw new IllegalStateException("Screen playlist store is unavailable");
        Session old = sessions.remove(key);
        if (old == null) return selection.getAsBoolean();
        boolean wasDirty = dirty; dirty = true;
        try { save(); } catch (RuntimeException failure) { sessions.put(key, old); dirty = wasDirty; throw failure; }
        boolean selected;
        try { selected = selection.getAsBoolean(); }
        catch (RuntimeException failure) {
            sessions.put(key, old); dirty = true;
            try { save(); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); healthy = false; }
            throw failure;
        }
        if (!selected) {
            sessions.put(key, old); dirty = true; save(); return false;
        }
        ScreenEvents.playbackChanged(key, capture(old), PlaybackSnapshot.EMPTY, false);
        return true;
    }

    /** Removes a deleted image from all libraries, queues, suspended announcements and active snapshots. */
    public synchronized boolean purgeImage(UUID image) {
        Objects.requireNonNull(image);
        if (!healthy) throw new IllegalStateException("Screen playlist store is unavailable");
        Map<UUID, PlaylistLibrarySnapshot> oldLibraries = new HashMap<>(libraries);
        Map<String, Session> oldSessions = new HashMap<>(sessions);
        boolean changed = false;
        for (Map.Entry<UUID, PlaylistLibrarySnapshot> entry : libraries.entrySet()) {
            PlaylistLibrarySnapshot library = entry.getValue();
            List<NamedPlaylist> next = new ArrayList<>(); boolean edited = false;
            for (NamedPlaylist list : library.playlists()) {
                List<PlaylistSlide> retained = list.slides().stream().filter(s -> !s.image().equals(image)).toList();
                edited |= retained.size() != list.slides().size();
                next.add(new NamedPlaylist(list.id(), list.name(), retained));
            }
            if (edited) { entry.setValue(new PlaylistLibrarySnapshot(increment(library.revision()), next)); changed = true; }
        }
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session next = entry.getValue().copy();
            if (purge(next, image)) { next.revision = increment(next.revision); entry.setValue(next); changed = true; }
        }
        if (!changed) return false;
        boolean wasDirty = dirty; dirty = true;
        try { save(); } catch (RuntimeException failure) {
            libraries.clear(); libraries.putAll(oldLibraries); sessions.clear(); sessions.putAll(oldSessions);
            dirty = wasDirty; throw failure;
        }
        for (var entry : sessions.entrySet()) {
            Session previous = oldSessions.get(entry.getKey());
            if (previous != null)
                ScreenEvents.playbackChanged(entry.getKey(), capture(previous), capture(entry.getValue()), false);
        }
        return true;
    }
    private static boolean purge(Session state, UUID image) {
        if (state.announcement) {
            boolean changed = purge(state.suspended, image);
            if (state.queuedSlide.image().equals(image)) { state.restore(); return true; }
            return changed;
        }
        boolean changed = state.queue.removeIf(slide -> slide.image().equals(image));
        boolean removedCurrentQueue = state.queuedSlide != null && state.queuedSlide.image().equals(image);
        int selected = state.slideIndex();
        boolean removedCanonical = selected >= 0 && state.slides.get(selected).image().equals(image);
        boolean removedCurrentBase = state.queuedSlide == null && removedCanonical;
        List<PlaylistSlide> slides = new ArrayList<>(); int[] mapped = new int[state.slides.size()];
        Arrays.fill(mapped, -1);
        for (int i = 0; i < state.slides.size(); i++) {
            PlaylistSlide slide = state.slides.get(i);
            if (!slide.image().equals(image)) { mapped[i] = slides.size(); slides.add(slide); }
        }
        if (slides.size() != state.slides.size()) {
            changed = true;
            List<Integer> order = new ArrayList<>(); int cursor = -1;
            for (int walk = 0; walk < state.order.size(); walk++) {
                int retained = mapped[state.order.get(walk)];
                if (retained >= 0) { order.add(retained); if (walk <= state.cursor) cursor = order.size() - 1; }
            }
            state.slides = List.copyOf(slides); state.order = List.copyOf(order); state.cursor = cursor;
        }
        if (removedCanonical && state.queuedSlide != null) state.advanceCanonicalOnce = true;
        if (removedCurrentQueue) { state.queuedSlide = null; changed = true; }
        if ((removedCurrentBase || removedCurrentQueue) && state.status != Status.STOPPED) {
            Status previous = state.status;
            // The removed canonical item has no repeat-current identity; continue to its successor.
            state.advance(removedCanonical);
            if (previous == Status.PAUSED && state.status == Status.PLAYING) state.status = Status.PAUSED;
        }
        return changed;
    }

    /** Load is fail-closed; a corrupt primary can only recover from a fully validated backup. */
    public synchronized void init(Path worldRoot) {
        Path next = Objects.requireNonNull(worldRoot).resolve(FILE_NAME).toAbsolutePath().normalize();
        if (healthy && next.equals(file)) return;
        healthy = false;
        Loaded loaded;
        if (Files.exists(next) || Files.exists(backup(next))) {
            try { loaded = read(next); }
            catch (Exception primary) {
                try {
                    loaded = read(backup(next));
                    atomicWrite(next, Files.readAllBytes(backup(next)));
                } catch (Exception secondary) {
                    secondary.addSuppressed(primary);
                    throw new IllegalStateException("Screen playlists and backup are unreadable; refusing to overwrite saved data", secondary);
                }
            }
        } else loaded = new Loaded(Map.of(), Map.of());
        libraries.clear(); libraries.putAll(loaded.libraries());
        sessions.clear(); sessions.putAll(loaded.sessions()); file = next; healthy = true; dirty = true;
        try { save(); } catch (RuntimeException failure) { healthy = false; throw failure; }
    }
    public synchronized void save() {
        if (!healthy) throw new IllegalStateException("Screen playlist store is unavailable");
        if (!dirty || file == null) return;
        try {
            byte[] bytes = encode();
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) atomicWrite(backup(file), Files.readAllBytes(file));
            else atomicWrite(backup(file), bytes);
            atomicWrite(file, bytes); dirty = false;
        } catch (IOException failure) { throw new IllegalStateException("Cannot persist screen playlists", failure); }
    }
    private record Loaded(Map<UUID, PlaylistLibrarySnapshot> libraries, Map<String, Session> sessions) { }
    private byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new FilterOutputStream(bytes) {
            private int count;
            private void reserve(int length) throws IOException {
                if (length < 0 || count > MAX_STORE_BYTES - 20 - length) throw new IOException("Playlist store exceeds size limit");
                count += length;
            }
            @Override public void write(int value) throws IOException { reserve(1); this.out.write(value); }
            @Override public void write(byte[] data, int start, int length) throws IOException { reserve(length); this.out.write(data, start, length); }
        })) {
            out.writeInt(libraries.size());
            for (UUID owner : libraries.keySet().stream().sorted().toList()) {
                writeUuid(out, owner); PlaylistLibrarySnapshot library = libraries.get(owner);
                out.writeLong(library.revision()); out.writeInt(library.playlists().size());
                for (NamedPlaylist playlist : library.playlists()) {
                    writeUuid(out, playlist.id()); out.writeUTF(playlist.name()); writeSlides(out, playlist.slides());
                }
            }
            out.writeInt(sessions.size());
            for (String key : sessions.keySet().stream().sorted().toList()) {
                out.writeUTF(key); writeSession(out, sessions.get(key));
            }
        }
        byte[] payload = bytes.toByteArray(); CRC32 crc = new CRC32(); crc.update(payload);
        ByteArrayOutputStream framed = new ByteArrayOutputStream(payload.length + 20);
        try (DataOutputStream out = new DataOutputStream(framed)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(payload.length); out.writeLong(crc.getValue()); out.write(payload);
        }
        return framed.toByteArray();
    }
    private static Loaded read(Path path) throws IOException {
        long length = Files.size(path);
        if (length < 20 || length > MAX_STORE_BYTES) throw new IOException("Invalid playlist store size");
        byte[] bytes = Files.readAllBytes(path);
        try (DataInputStream framed = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (framed.readInt() != MAGIC) throw new IOException("Unsupported playlist store");
            int version = framed.readInt();
            if (version != 1 && version != VERSION) throw new IOException("Unsupported playlist store");
            int payloadLength = framed.readInt(); long checksum = framed.readLong();
            if (payloadLength != bytes.length - 20) throw new IOException("Invalid playlist framing");
            byte[] payload = framed.readNBytes(payloadLength); CRC32 crc = new CRC32(); crc.update(payload);
            if (crc.getValue() != checksum) throw new IOException("Playlist checksum mismatch");
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
                Map<UUID, PlaylistLibrarySnapshot> libraries = new HashMap<>();
                for (int n = count(in, MAX_OWNERS); n > 0; n--) {
                    UUID owner = readUuid(in); long revision = in.readLong();
                    List<NamedPlaylist> lists = new ArrayList<>();
                    for (int p = count(in, PlaylistLibrarySnapshot.MAX_PLAYLISTS); p > 0; p--)
                        lists.add(new NamedPlaylist(readUuid(in), in.readUTF(), readSlides(in, NamedPlaylist.MAX_SLIDES)));
                    if (libraries.put(owner, new PlaylistLibrarySnapshot(revision, lists)) != null) throw new IOException("Duplicate owner");
                }
                Map<String, Session> sessions = new HashMap<>();
                for (int n = count(in, MAX_NETWORKS); n > 0; n--) {
                    String key = in.readUTF();
                    if (!validKey(key) || sessions.put(key, readSession(in, 0, version)) != null) throw new IOException("Invalid playback key");
                }
                if (in.available() != 0) throw new IOException("Trailing playlist data");
                return new Loaded(libraries, sessions);
            }
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new IOException("Invalid playlist data", invalid); }
    }
    private static void writeSession(DataOutputStream out, Session state) throws IOException {
        out.writeBoolean(state.ownsContent);
        writeNullableUuid(out, state.sourceOwner); writeNullableUuid(out, state.sourceId);
        writeSlides(out, state.slides); out.writeInt(state.order.size());
        for (int index : state.order) out.writeInt(index);
        out.writeInt(state.cursor); out.writeLong(state.offset); out.writeByte(state.status.ordinal());
        out.writeByte(state.repeat.ordinal()); out.writeBoolean(state.shuffle); out.writeLong(state.shuffleSeed);
        writeSlides(out, state.queue); out.writeBoolean(state.queuedSlide != null);
        if (state.queuedSlide != null) writeSlide(out, state.queuedSlide);
        out.writeBoolean(state.advanceCanonicalOnce);
        out.writeLong(state.revision); out.writeBoolean(state.announcement);
        if (state.announcement) writeSession(out, state.suspended);
    }
    private static Session readSession(DataInputStream in, int depth, int version) throws IOException {
        if (depth > 1) throw new IOException("Nested announcement");
        Session state = new Session();
        state.ownsContent = version >= 2 && in.readBoolean();
        state.sourceOwner = readNullableUuid(in); state.sourceId = readNullableUuid(in);
        state.slides = readSlides(in, NamedPlaylist.MAX_SLIDES);
        List<Integer> order = new ArrayList<>();
        for (int n = count(in, NamedPlaylist.MAX_SLIDES); n > 0; n--) order.add(in.readInt());
        state.order = List.copyOf(order); state.cursor = in.readInt(); state.offset = in.readLong();
        state.status = value(Status.values(), in.readUnsignedByte()); state.repeat = value(RepeatMode.values(), in.readUnsignedByte());
        state.shuffle = in.readBoolean(); state.shuffleSeed = in.readLong(); state.queue = new ArrayList<>(readSlides(in, MAX_QUEUE));
        state.queuedSlide = in.readBoolean() ? readSlide(in) : null;
        state.advanceCanonicalOnce = in.readBoolean();
        state.revision = in.readLong(); state.announcement = in.readBoolean();
        if (state.announcement) state.suspended = readSession(in, depth + 1, version);
        if (version == 1) state.ownsContent = state.status != Status.STOPPED || !state.slides.isEmpty();
        validateSession(state, depth); return state;
    }
    private static void validateSession(Session state, int depth) {
        if (depth > 1 || state.revision < 0 || state.offset < 0 || state.slides.size() > NamedPlaylist.MAX_SLIDES
                || state.order.size() != state.slides.size() || state.cursor < -1 || state.cursor >= state.order.size()
                || state.queue.size() > MAX_QUEUE || state.status == null || state.repeat == null
                || (state.sourceOwner == null) != (state.sourceId == null)
                || !state.slides.isEmpty() && state.sourceOwner == null
                || state.advanceCanonicalOnce && state.queuedSlide == null) throw new IllegalArgumentException("Invalid playback state");
        Set<Integer> indices = new HashSet<>();
        for (int index : state.order) if (index < 0 || index >= state.slides.size() || !indices.add(index))
            throw new IllegalArgumentException("Invalid shuffle order");
        if (!state.shuffle) for (int i = 0; i < state.order.size(); i++)
            if (state.order.get(i) != i) throw new IllegalArgumentException("Invalid canonical order");
        if (state.announcement != (state.suspended != null) || state.announcement && (state.queuedSlide == null
                || !state.slides.isEmpty() || !state.queue.isEmpty() || state.status == Status.STOPPED))
            throw new IllegalArgumentException("Invalid announcement");
        if (state.suspended != null) validateSession(state.suspended, depth + 1);
        PlaylistSlide current = state.current();
        if (state.status != Status.STOPPED && (current == null || state.offset >= current.durationMillis())
                || state.status == Status.STOPPED && (state.offset != 0 || state.cursor != -1 || state.queuedSlide != null)) throw new IllegalArgumentException("Invalid playback offset");
    }
    private static <E> E value(E[] values, int ordinal) throws IOException {
        if (ordinal < 0 || ordinal >= values.length) throw new IOException("Invalid playback enum");
        return values[ordinal];
    }
    private static void writeSlides(DataOutputStream out, List<PlaylistSlide> slides) throws IOException {
        out.writeInt(slides.size()); for (PlaylistSlide slide : slides) writeSlide(out, slide);
    }
    private static List<PlaylistSlide> readSlides(DataInputStream in, int max) throws IOException {
        List<PlaylistSlide> result = new ArrayList<>();
        for (int n = count(in, max); n > 0; n--) result.add(readSlide(in));
        return List.copyOf(result);
    }
    private static void writeSlide(DataOutputStream out, PlaylistSlide slide) throws IOException { writeUuid(out, slide.image()); out.writeLong(slide.durationMillis()); }
    private static PlaylistSlide readSlide(DataInputStream in) throws IOException { return new PlaylistSlide(readUuid(in), in.readLong()); }
    private static int count(DataInputStream in, int max) throws IOException {
        int count = in.readInt(); if (count < 0 || count > max) throw new IOException("Invalid collection count"); return count;
    }
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void writeNullableUuid(DataOutputStream out, UUID id) throws IOException { out.writeBoolean(id != null); if (id != null) writeUuid(out, id); }
    private static UUID readNullableUuid(DataInputStream in) throws IOException { return in.readBoolean() ? readUuid(in) : null; }
    private static long increment(long revision) { return Math.addExact(revision, 1); }
    private static boolean validKey(String key) {
        if (key == null) return false;
        if (key.startsWith("network/")) return key.substring(8).matches("[A-Za-z0-9._:-]{1,64}");
        if (key.startsWith("display/")) {
            try { return key.equals("display/" + UUID.fromString(key.substring(8))); }
            catch (IllegalArgumentException ignored) { return false; }
        }
        return false;
    }
    private static Path backup(Path path) { return path.resolveSibling(path.getFileName() + ".bak"); }
    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer data = ByteBuffer.wrap(bytes); while (data.hasRemaining()) channel.write(data); channel.force(true);
        }
        try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            // Fail rather than silently weakening the crash-safety guarantee of this store.
            throw new IOException("Atomic playlist persistence is not supported by this filesystem", unsupported);
        }
    }
}
