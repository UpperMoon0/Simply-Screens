package com.nstut.simplyscreens;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Server-owned state. Call mutations on the server thread; snapshots never expose mutable collections. */
public final class ScreenNetworkService {
    public enum Policy { PUBLIC, TRUSTED, OWNER_ONLY, OPERATORS }
    public enum Permission { VIEW, CONTROL, MANAGE }
    public record Actor(UUID id, boolean operator) {
        public static Actor automation() { return new Actor(null, false); }
    }
    public record State(UUID owner, Set<UUID> trusted, Policy view, Policy control,
                        UUID image, String mode, int slide, long offsetMillis, long revision) {
        public State {
            trusted = Set.copyOf(trusted);
            Objects.requireNonNull(view);
            Objects.requireNonNull(control);
            if (trusted.size() > 64 || revision < 0 || slide < 0 || offsetMillis < 0 || !"STATIC".equals(mode))
                throw new IllegalArgumentException("Invalid screen network state");
        }
        State revise(UUID nextOwner, Set<UUID> nextTrusted, Policy nextView, Policy nextControl, UUID nextImage) {
            return new State(nextOwner, nextTrusted, nextView, nextControl, nextImage, mode, slide, offsetMillis,
                    Math.addExact(revision, 1));
        }
    }
    public record DisplayState(String screenId, boolean override, boolean aspect) {
        public DisplayState {
            if (screenId == null || !screenId.equals(ScreenRegistryHelper.normalizeScreenId(screenId)))
                throw new IllegalArgumentException("Invalid physical screen link");
        }
    }
    private record Store(int version, Map<String, State> states, Map<UUID, DisplayState> displays, Map<String, UUID> anchors) { }
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, State> states = new HashMap<>();
    private final Map<UUID, DisplayState> displays = new HashMap<>();
    private final Map<String, UUID> anchors = new HashMap<>();
    private Path file;
    private boolean healthy = true;
    private void requireHealthy() {
        if (!healthy) throw new IllegalStateException("Screen network store is unavailable");
    }

    public static String network(String id) {
        String normalized = ScreenRegistryHelper.normalizeScreenId(id);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Invalid Screen ID");
        return "network/" + normalized;
    }
    public static String display(UUID id) { return "display/" + Objects.requireNonNull(id); }
    public synchronized State snapshot(String key) { return healthy ? states.get(key) : null; }
    public synchronized Set<String> keys() { return healthy ? Set.copyOf(states.keySet()) : Set.of(); }
    public synchronized DisplayState displayState(UUID id) { return healthy ? displays.get(id) : null; }
    public synchronized UUID displayAt(String anchor) { return healthy ? anchors.get(anchor) : null; }
    synchronized void registerAnchor(String anchor, UUID id) {
        requireHealthy();
        UUID old = anchors.put(anchor, id);
        if (id.equals(old)) return;
        try { save(); } catch (RuntimeException failure) {
            if (old == null) anchors.remove(anchor); else anchors.put(anchor, old);
            throw failure;
        }
    }
    synchronized void updateDisplay(UUID id, String screenId, boolean override, boolean aspect) {
        requireHealthy();
        DisplayState next = new DisplayState(screenId, override, aspect);
        DisplayState previous = displays.put(id, next);
        if (next.equals(previous)) return;
        try { save(); } catch (RuntimeException failure) {
            if (previous == null) displays.remove(id); else displays.put(id, previous);
            throw failure;
        }
    }
    public synchronized State ensure(String key, UUID legacyImage, UUID legacyOwner) {
        requireHealthy();
        validateKey(key);
        State existing = states.get(key);
        if (existing != null) return existing;
        State created = new State(legacyOwner, Set.of(), Policy.PUBLIC, Policy.OWNER_ONLY,
                legacyImage, "STATIC", 0, 0, 0);
        commit(key, created);
        return created;
    }
    public synchronized boolean allowed(String key, Actor actor, Permission permission) {
        if (!healthy) return false;
        State s = states.get(key);
        if (s == null || actor == null) return false;
        if (actor.operator()) return true;
        if (s.owner() != null && s.owner().equals(actor.id())) return true;
        if (permission == Permission.MANAGE) return false;
        Policy p = permission == Permission.VIEW ? s.view() : s.control();
        return p == Policy.PUBLIC || p == Policy.TRUSTED && actor.id() != null && s.trusted().contains(actor.id());
    }
    public synchronized boolean claim(String key, Actor actor) {
        if (!healthy) return false;
        State s = states.get(key);
        if (s == null || actor == null || actor.id() == null) return false;
        if (s.owner() != null) return allowed(key, actor, Permission.MANAGE);
        // Legacy records may only be claimed by an operator. New placement uses createOwned.
        if (!actor.operator()) return false;
        commit(key, s.revise(actor.id(), Set.of(), s.view(), s.control(), s.image()));
        return true;
    }
    public synchronized boolean createOwned(String key, UUID owner, UUID image) {
        if (!healthy) return false;
        validateKey(key);
        if (owner == null || states.containsKey(key)) return false;
        if (key.startsWith("network/") && atNetworkLimit(owner)) return false;
        commit(key, new State(owner, Set.of(), Policy.PUBLIC, Policy.OWNER_ONLY, image, "STATIC", 0, 0, 0));
        return true;
    }
    public synchronized boolean select(String key, Actor actor, UUID image, long expectedRevision) {
        if (!healthy) return false;
        State s = states.get(key);
        if (s == null || s.revision() != expectedRevision || !allowed(key, actor, Permission.CONTROL)) return false;
        if (!Objects.equals(image, s.image())) commit(key, s.revise(s.owner(), s.trusted(), s.view(), s.control(), image));
        return true;
    }
    public synchronized boolean access(String key, Actor actor, long expectedRevision, String action, String value) {
        if (!healthy) return false;
        State s = states.get(key);
        if (s == null || s.revision() != expectedRevision || !allowed(key, actor, Permission.MANAGE)) return false;
        UUID owner = s.owner();
        Set<UUID> trusted = new HashSet<>(s.trusted());
        Policy view = s.view(), control = s.control();
        try {
            switch (action) {
                case "VIEW" -> view = Policy.valueOf(value);
                case "CONTROL" -> control = Policy.valueOf(value);
                case "TRUST" -> { if (trusted.size() >= 64) return false; trusted.add(UUID.fromString(value)); }
                case "UNTRUST" -> trusted.remove(UUID.fromString(value));
                case "TRANSFER" -> {
                    UUID recipient = UUID.fromString(value);
                    if (key.startsWith("network/") && !recipient.equals(owner) && atNetworkLimit(recipient)) return false;
                    owner = recipient;
                    trusted.clear();
                }
                default -> { return false; }
            }
        } catch (IllegalArgumentException invalid) { return false; }
        commit(key, s.revise(owner, trusted, view, control, s.image()));
        return true;
    }
    private boolean atNetworkLimit(UUID owner) {
        return states.entrySet().stream()
                .filter(e -> e.getKey().startsWith("network/") && owner.equals(e.getValue().owner()))
                .limit(ScreenRegistryHelper.MAX_SCREEN_IDS_PER_PLAYER)
                .count() >= ScreenRegistryHelper.MAX_SCREEN_IDS_PER_PLAYER;
    }
    /** Internal reconciliation only. External callers must use select with an actor and revision. */
    synchronized void reconcileImage(String key, UUID image) {
        State s = ensure(key, null, null);
        if (!Objects.equals(s.image(), image)) commit(key, s.revise(s.owner(), s.trusted(), s.view(), s.control(), image));
    }
    /** Internal structure split/detach: preserve access while giving the physical display independent content. */
    synchronized UUID forkDisplay(String source, UUID image) {
        requireHealthy();
        State s = Objects.requireNonNull(states.get(source), "Missing source display");
        UUID id = UUID.randomUUID();
        commit(display(id), new State(s.owner(), s.trusted(), s.view(), s.control(), image,
                "STATIC", 0, 0, 0));
        return id;
    }
    /** Internal unlink: retain the physical identity so unloaded children follow the detached display. */
    synchronized void detachDisplay(UUID id, String source, UUID image) {
        requireHealthy();
        State inherited = Objects.requireNonNull(states.get(source), "Missing source display");
        String key = display(id);
        State previous = states.get(key);
        long revision = previous == null ? 0 : Math.addExact(previous.revision(), 1);
        commit(key, new State(inherited.owner(), inherited.trusted(), inherited.view(), inherited.control(),
                image, "STATIC", 0, 0, revision));
    }
    synchronized void remove(String key) {
        requireHealthy();
        State old = states.remove(key);
        try { save(); } catch (RuntimeException failure) { if (old != null) states.put(key, old); throw failure; }
    }
    private void commit(String key, State state) {
        requireHealthy();
        State previous = states.put(key, state);
        try { save(); } catch (RuntimeException failure) {
            if (previous == null) states.remove(key); else states.put(key, previous);
            throw failure;
        }
    }
    public synchronized void init(Path root, Map<String, UUID> images, Map<String, UUID> owners) {
        Path next = root.resolve("screen_networks.json").toAbsolutePath().normalize();
        if (healthy && next.equals(file)) return;
        healthy = false;
        Map<String, State> loaded = new HashMap<>();
        Map<UUID, DisplayState> loadedDisplays = new HashMap<>();
        Map<String, UUID> loadedAnchors = new HashMap<>();
        boolean exists = Files.exists(next) || Files.exists(backup(next));
        if (exists) {
            Store store;
            try { store = read(next); } catch (Exception primary) {
                try { store = read(backup(next)); Files.copy(backup(next), next, StandardCopyOption.REPLACE_EXISTING); }
                catch (Exception secondary) { throw new IllegalStateException("Screen network state and backup are unreadable; refusing unsafe legacy fallback", secondary); }
            }
            loaded.putAll(store.states());
            if (store.displays() != null) loadedDisplays.putAll(store.displays());
            if (store.anchors() != null) loadedAnchors.putAll(store.anchors());
        } else {
            Set<String> ids = new HashSet<>(images.keySet()); ids.addAll(owners.keySet());
            for (String id : ids) if (!ScreenRegistryHelper.normalizeScreenId(id).isEmpty())
                loaded.put(network(id), new State(owners.get(id), Set.of(), Policy.PUBLIC, Policy.OWNER_ONLY,
                        images.get(id), "STATIC", 0, 0, 0));
        }
        states.clear(); states.putAll(loaded); displays.clear(); displays.putAll(loadedDisplays);
        anchors.clear(); anchors.putAll(loadedAnchors); file = next;
        healthy = true;
        try { save(); } catch (RuntimeException failure) { healthy = false; throw failure; }
    }
    private static Store read(Path path) throws IOException {
        Store raw = JSON.fromJson(Files.readString(path), Store.class);
        if (raw == null || raw.version() != 1 || raw.states() == null) throw new IOException("Unsupported network store");
        for (var e : raw.states().entrySet()) {
            validateKey(e.getKey());
            State s = Objects.requireNonNull(e.getValue());
            // Reconstruct to validate even with serializers that bypass record constructors.
            new State(s.owner(), s.trusted(), s.view(), s.control(), s.image(), s.mode(), s.slide(), s.offsetMillis(), s.revision());
        }
        if (raw.displays() != null) for (var e : raw.displays().entrySet()) {
            Objects.requireNonNull(e.getKey());
            DisplayState d = Objects.requireNonNull(e.getValue());
            new DisplayState(d.screenId(), d.override(), d.aspect());
        }
        if (raw.anchors() != null) raw.anchors().forEach((anchor, id) -> {
            Objects.requireNonNull(anchor); Objects.requireNonNull(id);
        });
        return raw;
    }
    private static void validateKey(String key) {
        if (key == null) throw new IllegalArgumentException("Missing target");
        if (key.startsWith("network/") && key.length() > 8 && key.equals(networkUnchecked(key.substring(8)))) return;
        if (key.startsWith("display/")) { if (key.equals(display(UUID.fromString(key.substring(8))))) return; }
        throw new IllegalArgumentException("Invalid target");
    }
    private static String networkUnchecked(String id) { return "network/" + ScreenRegistryHelper.normalizeScreenId(id); }
    private static Path backup(Path p) { return p.resolveSibling(p.getFileName() + ".bak"); }
    public synchronized void save() {
        requireHealthy();
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.toJson(new Store(1, states, displays, anchors)));
            if (Files.exists(file)) Files.copy(file, backup(file), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Cannot persist screen network state", e); }
    }
}
