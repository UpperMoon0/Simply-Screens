package com.nstut.simplyscreens.api;

import com.nstut.simplyscreens.ScreenNetworkService;
import com.nstut.simplyscreens.ScreenNetworkService.Actor;
import com.nstut.simplyscreens.playlist.PlaylistSlide;
import com.nstut.simplyscreens.playlist.ScreenPlaylistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ScreenControlServiceTest {
    @Test void staticSelectionDoesNotCommitWhenCancellationCannotPersist(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        networks.init(root, java.util.Map.of(), java.util.Map.of());
        assertTrue(networks.createOwned(key, owner, image));
        playlists.init(root);
        UUID saved = playlist();
        assertTrue(api.play(owningPlayer, key, owner, saved, id -> true));
        var before = playlists.snapshot(key);
        java.nio.file.Files.createDirectory(root.resolve(ScreenPlaylistService.FILE_NAME + ".bak.tmp"));
        assertThrows(IllegalStateException.class, () -> api.select(owningPlayer, key, nextImage, id -> true));
        assertEquals(image, networks.snapshot(key).image());
        assertEquals(before, playlists.snapshot(key));
        var reloaded = new ScreenNetworkService(); reloaded.init(root, java.util.Map.of(), java.util.Map.of());
        assertEquals(image, reloaded.snapshot(key).image());
    }

    @Test void failedStaticWriteRestoresPersistedPlayback(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        networks.init(root, java.util.Map.of(), java.util.Map.of());
        assertTrue(networks.createOwned(key, owner, image));
        playlists.init(root);
        assertTrue(api.play(owningPlayer, key, owner, playlist(), id -> true));
        var before = playlists.snapshot(key);
        java.nio.file.Files.createDirectory(root.resolve("screen_networks.json.tmp"));
        ScreenEvents.resetPending();
        assertThrows(IllegalStateException.class, () -> api.select(owningPlayer, key, nextImage, id -> true));
        assertEquals(image, networks.snapshot(key).image());
        assertEquals(before, playlists.snapshot(key));
        var reloaded = new ScreenPlaylistService(); reloaded.init(root);
        assertEquals(before, reloaded.snapshot(key));
        List<ScreenEvents.Change> events = new ArrayList<>();
        try (var listener = ScreenEvents.listen(events::add)) { ScreenEvents.flush(error -> fail(error)); }
        assertTrue(events.isEmpty());
    }
    private final UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
    private final UUID image = UUID.randomUUID(), nextImage = UUID.randomUUID();
    private final String key = ScreenNetworkService.network("billboard");
    private final Actor owningPlayer = new Actor(owner, false);
    private final Actor visitor = new Actor(stranger, false);
    private final ScreenNetworkService networks = new ScreenNetworkService();
    private final ScreenPlaylistService playlists = new ScreenPlaylistService();
    private final ScreenControlService api = new ScreenControlService(networks, playlists);

    @BeforeEach void setup() {
        ScreenEvents.resetPending();
        assertTrue(networks.createOwned(key, owner, image));
    }

    private UUID playlist() {
        UUID playlist = playlists.create(owner, "Announcements", 0);
        assertNotNull(playlist);
        assertTrue(playlists.replaceSlides(owner, playlist, List.of(
                new PlaylistSlide(image, 1000), new PlaylistSlide(nextImage, 1000)), 1));
        return playlist;
    }

    @Test void inaccessibleControlAndMediaAreBothDenied() {
        assertNotNull(api.status(key, visitor)); // Public viewing is not public control.
        assertNull(api.status(key, visitor).owner()); // Do not disclose management metadata.
        assertFalse(api.status(key, visitor).canControl());
        assertFalse(api.select(visitor, key, nextImage, id -> true));
        assertFalse(api.select(owningPlayer, key, nextImage, id -> false));
        assertEquals(image, networks.snapshot(key).image());
        assertFalse(api.transport(visitor, key, "STOP"));
        assertFalse(api.transport(owningPlayer, key, "INVALID"));
        assertEquals(1, api.list(visitor).size());
        assertTrue(api.list(visitor).stream().noneMatch(s -> s.owner() != null));
        assertEquals(com.nstut.simplyscreens.playlist.PlaybackSnapshot.EMPTY, api.playback(key, visitor));
    }

    @Test void anonymousAutomationOnlyOperatesExplicitlyPublicNetworksAndApprovedImages() {
        assertFalse(api.select(Actor.automation(), key, null, id -> true));
        assertTrue(networks.access(key, owningPlayer, 0, "VIEW", "OWNER_ONLY"));
        assertTrue(networks.access(key, owningPlayer, 1, "CONTROL", "PUBLIC"));
        var redacted = api.status(key, Actor.automation());
        assertNotNull(redacted);
        assertNull(redacted.image());
        assertNull(redacted.owner());
        assertFalse(api.select(Actor.automation(), key, nextImage, id -> false));
        assertTrue(api.select(Actor.automation(), key, null, id -> false));
        assertNull(networks.snapshot(key).image());
        assertFalse(api.play(Actor.automation(), key, owner, playlist(), id -> true));
        assertNull(api.status("network/missing", owningPlayer));
    }

    @Test void playlistSourceAndEverySlideMustBeAuthorized() {
        UUID saved = playlist();
        assertFalse(api.play(visitor, key, owner, saved, id -> true));
        assertFalse(api.play(owningPlayer, key, owner, saved, id -> !id.equals(nextImage)));
        assertFalse(api.play(owningPlayer, key, stranger, saved, id -> true));
        assertTrue(api.play(owningPlayer, key, owner, saved, id -> true));
        assertEquals(image, api.playback(key, owningPlayer).currentImage());
        assertTrue(api.transport(owningPlayer, key, "NEXT"));
        assertEquals(nextImage, api.playback(key, owningPlayer).currentImage());
        assertTrue(api.transport(owningPlayer, key, "PAUSE"));
        assertTrue(api.transport(owningPlayer, key, "RESUME"));
        assertTrue(api.select(owningPlayer, key, null, id -> true));
        assertEquals(com.nstut.simplyscreens.playlist.PlaybackSnapshot.Status.STOPPED,
                playlists.snapshot(key).status());
    }

    @Test void eventsAreLogicalOrderedAndNeverFireForRejectedWrites() throws Exception {
        List<ScreenEvents.Change> delivered = new ArrayList<>();
        try (AutoCloseable unused = ScreenEvents.listen(delivered::add)) {
            assertFalse(api.select(visitor, key, nextImage, id -> true));
            assertTrue(api.select(owningPlayer, key, nextImage, id -> true));
            assertTrue(api.select(owningPlayer, key, nextImage, id -> true)); // No-op.
            ScreenEvents.flush(error -> fail(error.getMessage()));
            assertEquals(List.of(ScreenEvents.Type.IMAGE_CHANGED),
                    delivered.stream().map(ScreenEvents.Change::type).toList());
            assertEquals(nextImage, delivered.get(0).image());
            delivered.clear();

            UUID saved = playlist();
            assertTrue(api.play(owningPlayer, key, owner, saved, id -> true));
            assertTrue(api.transport(owningPlayer, key, "PAUSE"));
            assertTrue(api.transport(owningPlayer, key, "RESUME"));
            playlists.tick(1000);
            playlists.tick(1000);
            ScreenEvents.flush(error -> fail(error.getMessage()));
            assertEquals(List.of(
                    ScreenEvents.Type.SLIDESHOW_STARTED,
                    ScreenEvents.Type.SLIDESHOW_PAUSED,
                    ScreenEvents.Type.SLIDESHOW_RESUMED,
                    ScreenEvents.Type.SLIDE_ADVANCED,
                    ScreenEvents.Type.SLIDESHOW_STOPPED,
                    ScreenEvents.Type.SLIDESHOW_COMPLETED),
                    delivered.stream().map(ScreenEvents.Change::type).toList());
        }
    }

    @Test void brokenListenerDoesNotPreventOthersAndSubscriptionCanBeClosed() throws Exception {
        List<ScreenEvents.Change> observed = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        AutoCloseable broken = ScreenEvents.listen(event -> { throw new IllegalStateException("intentional"); });
        AutoCloseable healthy = ScreenEvents.listen(observed::add);
        try {
            assertTrue(api.select(owningPlayer, key, nextImage, id -> true));
            ScreenEvents.flush(errors::add);
            assertEquals(1, errors.size());
            assertEquals(1, observed.size());
        } finally {
            broken.close();
            healthy.close();
        }
        assertTrue(api.select(owningPlayer, key, image, id -> true));
        ScreenEvents.flush(errors::add);
        assertEquals(1, observed.size());
    }
}
