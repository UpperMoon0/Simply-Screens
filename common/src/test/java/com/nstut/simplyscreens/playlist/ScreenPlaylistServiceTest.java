package com.nstut.simplyscreens.playlist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.util.zip.CRC32;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.nstut.simplyscreens.playlist.PlaybackSnapshot.Status;

class ScreenPlaylistServiceTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), x = UUID.randomUUID();
    private final String key = "network/lobby";
    private final ScreenPlaylistService service = new ScreenPlaylistService();

    private PlaylistSlide slide(UUID image) { return new PlaylistSlide(image, 1_000); }
    private long revision() { return service.snapshot(key).revision(); }
    private long libraryRevision() { return service.library(owner).revision(); }
    private UUID saved(UUID... images) {
        UUID id = service.create(owner, "Show " + libraryRevision(), libraryRevision());
        assertNotNull(id);
        assertTrue(service.replaceSlides(owner, id, Arrays.stream(images).map(this::slide).toList(), libraryRevision()));
        return id;
    }
    private UUID playing(UUID... images) {
        UUID id = saved(images); assertTrue(service.play(key, owner, id, revision())); return id;
    }
    private void command(String action) { assertTrue(service.command(key, action, revision())); }
    private void queue(UUID image, boolean next) { assertTrue(service.queue(key, slide(image), next, revision())); }
    private void mode(RepeatMode repeat, boolean shuffle) { assertTrue(service.modes(key, repeat, shuffle, revision())); }

    @Test void libraryCrudIsOwnerScopedRevisionCheckedAndNamesAreUnique() {
        UUID id = saved(a, b);
        assertNull(service.create(owner, "Show 0", libraryRevision()));
        assertNull(service.create(owner, "show 0", libraryRevision()));
        assertFalse(service.rename(owner, id, "Wrong", 0));
        assertFalse(service.rename(other, id, "Stolen", 0));
        assertFalse(service.delete(other, id, 0));
        assertFalse(service.replaceSlides(other, id, List.of(slide(c)), 0));
        assertNull(service.duplicate(other, id, "Stolen", 0));
        assertTrue(service.rename(owner, id, "Arrival", libraryRevision()));
        UUID copy = service.duplicate(owner, id, "Arrival copy", libraryRevision());
        assertNotNull(copy); assertNotEquals(id, copy);
        assertEquals(service.library(owner).find(id).slides(), service.library(owner).find(copy).slides());
        assertTrue(service.delete(owner, id, libraryRevision()));
        assertNull(service.library(owner).find(id));
        assertEquals(1, service.library(owner).playlists().size());
        assertTrue(service.library(other).playlists().isEmpty());
    }
    @Test void validatesNamesCountsDurationsAndImmutableViews() {
        assertThrows(IllegalArgumentException.class, () -> new PlaylistSlide(a, 999));
        assertThrows(IllegalArgumentException.class, () -> new PlaylistSlide(a, 86_400_001));
        assertThrows(NullPointerException.class, () -> new PlaylistSlide(null, 1_000));
        assertNull(service.create(owner, " ", 0));
        assertNull(service.create(owner, " trailing ", 0));
        assertNull(service.create(owner, "line\nbreak", 0));
        assertNull(service.create(owner, "x".repeat(65), 0));
        UUID first = saved(a);
        assertFalse(service.replaceSlides(owner, first, Collections.nCopies(257, slide(a)), libraryRevision()));
        assertTrue(service.replaceSlides(owner, first, Collections.nCopies(256, slide(a)), libraryRevision()));
        for (int n = 1; n < 64; n++) assertNotNull(service.create(owner, "List " + n, libraryRevision()));
        assertNull(service.create(owner, "Overflow", libraryRevision()));
        assertNull(service.duplicate(owner, first, "Overflow", libraryRevision()));
        assertThrows(UnsupportedOperationException.class, () -> service.library(owner).playlists().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.library(owner).find(first).slides().clear());
    }
    @Test void editingAndDeletingSavedListCannotMutatePlaybackSnapshot() {
        UUID id = playing(a, b);
        assertTrue(service.replaceSlides(owner, id, List.of(slide(c)), libraryRevision()));
        assertEquals(List.of(slide(a), slide(b)), service.snapshot(key).slides());
        assertTrue(service.delete(owner, id, libraryRevision()));
        service.tick(1_000); assertEquals(b, service.snapshot(key).currentImage());
        command("STOP"); command("RESUME");
        assertEquals(a, service.snapshot(key).currentImage());
        assertFalse(service.play(key, owner, id, revision()));
    }
    @Test void usesTickElapsedAndOnlyChangesRevisionAtBoundaries() {
        playing(a, b, c); long original = revision();
        assertTrue(service.tick(400).isEmpty());
        assertEquals(400, service.snapshot(key).offsetMillis()); assertEquals(original, revision());
        assertTrue(service.tick(0).isEmpty());
        assertEquals(Set.of(key), service.tick(1_750).keySet());
        assertEquals(c, service.snapshot(key).currentImage());
        assertEquals(150, service.snapshot(key).offsetMillis()); assertEquals(original + 1, revision());
        assertFalse(service.command(key, "PAUSE", original));
        assertThrows(IllegalArgumentException.class, () -> service.tick(-1));
        service.tick(850); assertEquals(Status.STOPPED, service.snapshot(key).status());
        assertNull(service.snapshot(key).currentImage()); assertEquals(0, service.snapshot(key).offsetMillis());
    }
    @Test void pauseResumePreviousRestartAndStopHaveExplicitCursorBehavior() {
        playing(a, b, c); service.tick(350); command("PAUSE");
        long revision = revision(); service.tick(8_000);
        assertEquals(350, service.snapshot(key).offsetMillis()); assertEquals(revision, revision());
        command("NEXT"); assertEquals(b, service.snapshot(key).currentImage());
        assertEquals(Status.PAUSED, service.snapshot(key).status());
        command("RESUME"); service.tick(230); command("RESTART");
        assertEquals(b, service.snapshot(key).currentImage()); assertEquals(0, service.snapshot(key).offsetMillis());
        command("PREVIOUS"); assertEquals(a, service.snapshot(key).currentImage());
        queue(x, false); command("STOP");
        assertNull(service.snapshot(key).currentImage()); assertTrue(service.snapshot(key).queue().isEmpty());
        command("RESTART"); assertEquals(a, service.snapshot(key).currentImage());
        assertFalse(service.command(key, "UNKNOWN", revision()));
    }
    @Test void temporaryNextAndAppendQueueResumeCanonicalOrder() {
        playing(a, b, c); queue(c, false); queue(x, true);
        assertEquals(List.of(x, c), service.snapshot(key).upcomingImages());
        service.tick(1_000); assertEquals(x, service.snapshot(key).currentImage());
        assertTrue(service.snapshot(key).queued()); assertEquals(0, service.snapshot(key).slideIndex());
        assertEquals(List.of(c, b), service.snapshot(key).upcomingImages());
        service.tick(1_000); assertEquals(c, service.snapshot(key).currentImage());
        service.tick(1_000); assertEquals(b, service.snapshot(key).currentImage());
        assertFalse(service.snapshot(key).queued()); assertEquals(1, service.snapshot(key).slideIndex());
    }
    @Test void queueCanBeReorderedRemovedClearedAndIsBounded() {
        playing(a); queue(a, false); queue(b, false); queue(c, false);
        assertTrue(service.moveQueued(key, 2, 0, revision()));
        assertEquals(List.of(slide(c), slide(a), slide(b)), service.snapshot(key).queue());
        assertTrue(service.removeQueued(key, 1, revision()));
        assertEquals(List.of(slide(c), slide(b)), service.snapshot(key).queue());
        assertFalse(service.removeQueued(key, -1, revision()));
        assertFalse(service.moveQueued(key, 0, 2, revision()));
        assertTrue(service.clearQueue(key, revision()));
        for (int i = 0; i < 64; i++) queue(x, false);
        assertFalse(service.queue(key, slide(x), false, revision()));
        assertThrows(UnsupportedOperationException.class, () -> service.snapshot(key).queue().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.snapshots().clear());
    }
    @Test void canPlayAStandaloneQueueAndAnnouncement() {
        queue(x, false); command("RESUME");
        assertEquals(x, service.snapshot(key).currentImage());
        service.tick(1_000); assertEquals(Status.STOPPED, service.snapshot(key).status());
        assertTrue(service.announce(key, slide(a), revision()));
        assertTrue(service.snapshot(key).announcing());
        service.tick(1_000); assertEquals(Status.STOPPED, service.snapshot(key).status());
    }
    @Test void announcementResumesExactPausedOffsetAndQueueAndCanBeCancelled() {
        playing(a, b); service.tick(375); queue(c, false); command("PAUSE");
        assertTrue(service.announce(key, slide(x), revision()));
        assertEquals(x, service.snapshot(key).currentImage());
        assertEquals(List.of(a, c), service.snapshot(key).upcomingImages());
        service.tick(500);
        assertTrue(service.announce(key, slide(c), revision())); // replace only the announcement
        assertEquals(0, service.snapshot(key).offsetMillis());
        service.tick(1_500);
        assertFalse(service.snapshot(key).announcing()); assertEquals(Status.PAUSED, service.snapshot(key).status());
        assertEquals(a, service.snapshot(key).currentImage()); assertEquals(375, service.snapshot(key).offsetMillis());
        assertEquals(List.of(slide(c)), service.snapshot(key).queue());
        command("RESUME"); assertTrue(service.announce(key, slide(x), revision()));
        service.tick(200); command("CANCEL_ANNOUNCEMENT");
        assertEquals(375, service.snapshot(key).offsetMillis()); assertTrue(service.snapshot(key).playing());
        assertFalse(service.command(key, "CANCEL_ANNOUNCEMENT", revision()));
    }
    @Test void announcementConsumesRemainderOnlyAfterRestoringTheExactRunningCursor() {
        playing(a, b); service.tick(250);
        assertTrue(service.announce(key, slide(x), revision()));
        service.tick(1_100);
        assertEquals(a, service.snapshot(key).currentImage()); assertEquals(350, service.snapshot(key).offsetMillis());
        assertTrue(service.announce(key, slide(x), revision())); command("PAUSE");
        service.tick(50_000); assertEquals(0, service.snapshot(key).offsetMillis());
        command("RESUME"); command("NEXT");
        assertEquals(a, service.snapshot(key).currentImage()); assertEquals(350, service.snapshot(key).offsetMillis());
    }
    @Test void queueEditsDuringAnAnnouncementApplyToSuspendedQueue() {
        playing(a, b); assertTrue(service.announce(key, slide(x), revision()));
        queue(c, true); service.tick(1_000);
        assertEquals(List.of(slide(c)), service.snapshot(key).queue());
        service.tick(1_000); assertEquals(c, service.snapshot(key).currentImage());
    }
    @Test void repeatCurrentStillHonorsQueueAndExplicitNext() {
        playing(a, b); mode(RepeatMode.CURRENT, false);
        service.tick(3_400); assertEquals(a, service.snapshot(key).currentImage()); assertEquals(400, service.snapshot(key).offsetMillis());
        queue(x, false); queue(c, false);
        service.tick(600); assertEquals(x, service.snapshot(key).currentImage());
        assertEquals(List.of(c, a), service.snapshot(key).upcomingImages());
        service.tick(2_000); assertEquals(a, service.snapshot(key).currentImage());
        command("NEXT"); assertEquals(b, service.snapshot(key).currentImage());
        service.tick(2_400); assertEquals(b, service.snapshot(key).currentImage());
    }
    @Test void repeatAndHugeDeltasAreBoundedWithoutOverflow() {
        playing(a, b, c); mode(RepeatMode.LIST, false);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.tick(Long.MAX_VALUE));
        long remainder = Long.MAX_VALUE % 3_000;
        assertEquals(List.of(a, b, c).get((int) (remainder / 1_000)), service.snapshot(key).currentImage());
        assertEquals(remainder % 1_000, service.snapshot(key).offsetMillis());
        mode(RepeatMode.CURRENT, false); long offset = service.snapshot(key).offsetMillis();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.tick(Long.MAX_VALUE));
        assertEquals((offset + Long.MAX_VALUE % 1_000) % 1_000, service.snapshot(key).offsetMillis());
    }
    @Test void shuffleUsesOneBoundedPermutationAndCanReturnToCanonicalOrder() {
        UUID id = playing(a, b, c); mode(RepeatMode.NONE, true);
        // Starting a new play selects the start of the shuffled snapshot, rather than reshuffling on every tick.
        assertTrue(service.play(key, owner, id, revision()));
        Set<UUID> visited = new HashSet<>();
        for (int i = 0; i < 3; i++) { assertTrue(visited.add(service.snapshot(key).currentImage())); service.tick(1_000); }
        assertEquals(Set.of(a, b, c), visited); assertEquals(Status.STOPPED, service.snapshot(key).status());
        command("RESUME"); UUID current = service.snapshot(key).currentImage();
        mode(RepeatMode.NONE, false); assertEquals(current, service.snapshot(key).currentImage());
        assertEquals(List.of(a, b, c).indexOf(current), service.snapshot(key).slideIndex());
    }
    @Test void purgeRemovesEveryReferenceAndAdvancesDeletedActiveImage() {
        UUID list = playing(a, b, a, c); queue(a, false); queue(x, false);
        service.tick(250); assertTrue(service.purgeImage(a));
        assertEquals(List.of(slide(b), slide(c)), service.library(owner).find(list).slides());
        assertEquals(x, service.snapshot(key).currentImage()); // temporary queue still takes priority
        assertEquals(List.of(slide(b), slide(c)), service.snapshot(key).slides());
        service.tick(1_000); assertEquals(b, service.snapshot(key).currentImage());
        assertFalse(service.purgeImage(a));
        assertTrue(service.purgeImage(b)); assertEquals(c, service.snapshot(key).currentImage());
        assertTrue(service.purgeImage(c)); assertEquals(Status.STOPPED, service.snapshot(key).status());
    }
    @Test void purgeHandlesPausedCurrentQueuedAndSuspendedAnnouncementReferences() {
        playing(a, b); mode(RepeatMode.CURRENT, false); queue(x, false); service.tick(1_000);
        assertEquals(x, service.snapshot(key).currentImage());
        assertTrue(service.purgeImage(x)); assertEquals(a, service.snapshot(key).currentImage());
        service.tick(250); command("PAUSE");
        assertTrue(service.announce(key, slide(c), revision()));
        assertTrue(service.purgeImage(a));
        assertEquals(c, service.snapshot(key).currentImage());
        assertTrue(service.purgeImage(c));
        assertEquals(b, service.snapshot(key).currentImage()); assertEquals(Status.PAUSED, service.snapshot(key).status());
        assertEquals(0, service.snapshot(key).offsetMillis());
    }
    @Test void deletingCanonicalAnchorDuringQueueResumesItsSuccessorEvenInRepeatCurrent(@TempDir Path root) {
        service.init(root); playing(a, b, c); command("NEXT"); mode(RepeatMode.CURRENT, false);
        queue(x, false); service.tick(1_000); assertEquals(x, service.snapshot(key).currentImage());
        assertTrue(service.purgeImage(b)); service.save();
        ScreenPlaylistService reloaded = new ScreenPlaylistService(); reloaded.init(root);
        assertEquals(List.of(c), reloaded.snapshot(key).upcomingImages());
        reloaded.tick(1_000); assertEquals(c, reloaded.snapshot(key).currentImage());
        reloaded.tick(1_000); assertEquals(c, reloaded.snapshot(key).currentImage());
    }
    @Test void randomControlSequencesKeepSnapshotsAndPersistenceValid(@TempDir Path root) {
        service.init(root); UUID id = saved(a, b, c); Random random = new Random(31);
        List<UUID> images = List.of(a, b, c, x);
        for (int n = 0; n < 1_000; n++) {
            switch (random.nextInt(12)) {
                case 0 -> service.play(key, owner, id, revision());
                case 1 -> service.command(key, "PAUSE", revision());
                case 2 -> service.command(key, "RESUME", revision());
                case 3 -> service.command(key, "NEXT", revision());
                case 4 -> service.command(key, "PREVIOUS", revision());
                case 5 -> service.command(key, "STOP", revision());
                case 6 -> service.announce(key, slide(images.get(random.nextInt(4))), revision());
                case 7 -> service.command(key, "CANCEL_ANNOUNCEMENT", revision());
                case 8 -> service.queue(key, slide(images.get(random.nextInt(4))), random.nextBoolean(), revision());
                case 9 -> service.modes(key, RepeatMode.values()[random.nextInt(3)], random.nextBoolean(), revision());
                case 10 -> service.tick(random.nextInt(5_000));
                case 11 -> service.clearQueue(key, revision());
            }
            PlaybackSnapshot snapshot = service.snapshot(key);
            assertTrue(snapshot.offsetMillis() >= 0);
            assertTrue(snapshot.upcomingImages().size() <= 2);
            if (snapshot.currentImage() != null) assertFalse(snapshot.upcomingImages().contains(snapshot.currentImage()));
            if (snapshot.current() != null) assertTrue(snapshot.offsetMillis() < snapshot.current().durationMillis());
            if (n % 50 == 0) {
                service.save(); ScreenPlaylistService reloaded = new ScreenPlaylistService(); reloaded.init(root);
                assertEquals(snapshot, reloaded.snapshot(key));
            }
        }
    }
    @Test void persistsRunningPausedAndAnnouncementOffsetsWithoutCountingDowntime(@TempDir Path root) {
        service.init(root); UUID id = playing(a, b); mode(RepeatMode.LIST, true);
        service.tick(325); queue(c, false); command("PAUSE");
        assertTrue(service.announce(key, slide(x), revision())); service.tick(275); service.save();
        ScreenPlaylistService loaded = new ScreenPlaylistService(); loaded.init(root);
        assertEquals(service.library(owner), loaded.library(owner));
        assertEquals(service.snapshot(key), loaded.snapshot(key));
        assertEquals(id, loaded.snapshot(key).sourceId());
        loaded.tick(725); PlaybackSnapshot restored = loaded.snapshot(key);
        assertEquals(Status.PAUSED, restored.status()); assertEquals(325, restored.offsetMillis());
        assertEquals(List.of(slide(c)), restored.queue());
        assertTrue(loaded.command(key, "RESUME", restored.revision())); loaded.tick(150); loaded.save();
        ScreenPlaylistService running = new ScreenPlaylistService(); running.init(root);
        assertTrue(running.snapshot(key).playing()); assertEquals(475, running.snapshot(key).offsetMillis());
    }
    @Test void primaryCorruptionRecoversValidatedBackupAndDoubleCorruptionFailsClosed(@TempDir Path root) throws Exception {
        service.init(root); UUID id = saved(a, b);
        service.save(); long backupRevision = libraryRevision();
        assertTrue(service.rename(owner, id, "Latest", libraryRevision()));
        Path primary = root.resolve(ScreenPlaylistService.FILE_NAME);
        Files.writeString(primary, "corrupt");
        ScreenPlaylistService recovered = new ScreenPlaylistService(); recovered.init(root);
        assertEquals(backupRevision, recovered.library(owner).revision());
        assertEquals("Show 0", recovered.library(owner).find(id).name());
        Files.writeString(primary, "bad"); Files.writeString(root.resolve(ScreenPlaylistService.FILE_NAME + ".bak"), "bad");
        ScreenPlaylistService refused = new ScreenPlaylistService();
        assertThrows(IllegalStateException.class, () -> refused.init(root));
        assertFalse(refused.healthy()); assertTrue(refused.snapshots().isEmpty());
        assertNull(refused.create(owner, "Unsafe", 0)); assertFalse(refused.play(key, owner, id, 0));
        assertThrows(IllegalStateException.class, refused::save);
        assertEquals("bad", Files.readString(primary));
    }
    @Test void checksumAndFramingRejectMalformedAndOversizedStores(@TempDir Path root) throws Exception {
        Path primary = root.resolve(ScreenPlaylistService.FILE_NAME);
        Path backup = root.resolve(ScreenPlaylistService.FILE_NAME + ".bak");
        List<byte[]> malformed = new ArrayList<>();
        malformed.add(framedPayload(-1, 0));
        malformed.add(framedPayload(ScreenPlaylistService.MAX_OWNERS + 1, 0));
        malformed.add(framedPayload(0, ScreenPlaylistService.MAX_NETWORKS + 1));
        malformed.add(framedPayload(0, 0, 123)); // trailing payload data
        byte[] brokenChecksum = framedPayload(0, 0); brokenChecksum[brokenChecksum.length - 1] = 1;
        malformed.add(brokenChecksum);
        byte[] brokenVersion = framedPayload(0, 0); brokenVersion[7] = 2;
        malformed.add(brokenVersion);
        for (byte[] bytes : malformed) {
            Files.write(primary, bytes); Files.write(backup, bytes);
            ScreenPlaylistService refused = new ScreenPlaylistService();
            assertThrows(IllegalStateException.class, () -> refused.init(root));
            assertFalse(refused.healthy());
            assertArrayEquals(bytes, Files.readAllBytes(primary));
        }
        try (RandomAccessFile out = new RandomAccessFile(primary.toFile(), "rw")) {
            out.setLength(ScreenPlaylistService.MAX_STORE_BYTES + 1L);
        }
        ScreenPlaylistService refused = new ScreenPlaylistService();
        assertThrows(IllegalStateException.class, () -> refused.init(root));
        assertFalse(refused.healthy());
    }
    @Test void validChecksumCannotHideDuplicateOwnerIdentities(@TempDir Path root) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(2);
            for (int i = 0; i < 2; i++) {
                out.writeLong(owner.getMostSignificantBits()); out.writeLong(owner.getLeastSignificantBits());
                out.writeLong(0); out.writeInt(0);
            }
            out.writeInt(0);
        }
        byte[] framed = frame(bytes.toByteArray());
        Files.write(root.resolve(ScreenPlaylistService.FILE_NAME), framed);
        Files.write(root.resolve(ScreenPlaylistService.FILE_NAME + ".bak"), framed);
        assertThrows(IllegalStateException.class, () -> new ScreenPlaylistService().init(root));
    }
    private static byte[] framedPayload(int... values) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (int value : values) out.writeInt(value);
        }
        return frame(bytes.toByteArray());
    }
    private static byte[] frame(byte[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); CRC32 crc = new CRC32(); crc.update(payload);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x5353504C); out.writeInt(1); out.writeInt(payload.length); out.writeLong(crc.getValue()); out.write(payload);
        }
        return bytes.toByteArray();
    }
    @Test void failedPersistenceRollsBackLibraryAndPlaybackMutations(@TempDir Path root) throws Exception {
        service.init(root); UUID id = playing(a, b); service.tick(123);
        PlaylistLibrarySnapshot beforeLibrary = service.library(owner);
        PlaybackSnapshot beforePlayback = service.snapshot(key);
        Files.createDirectory(root.resolve(ScreenPlaylistService.FILE_NAME + ".bak.tmp"));
        assertThrows(IllegalStateException.class, () -> service.rename(owner, id, "Must not commit", libraryRevision()));
        assertEquals(beforeLibrary, service.library(owner));
        assertThrows(IllegalStateException.class, () -> service.command(key, "NEXT", revision()));
        assertEquals(beforePlayback, service.snapshot(key));
        assertThrows(IllegalStateException.class, () -> service.purgeImage(a));
        assertEquals(beforeLibrary, service.library(owner)); assertEquals(beforePlayback, service.snapshot(key));
        assertThrows(IllegalStateException.class, () -> service.removeNetwork(key));
        assertEquals(beforePlayback, service.snapshot(key));
    }
    @Test void rejectsUnboundedOrInvalidNetworkTargetsAndRemovesPersistedSessions(@TempDir Path root) {
        UUID id = saved(a);
        assertFalse(service.play("../../outside", owner, id, 0));
        assertFalse(service.play("network/", owner, id, 0));
        assertFalse(service.play("network/" + "a".repeat(65), owner, id, 0));
        assertFalse(service.play("display/not-a-uuid", owner, id, 0));
        for (int i = 0; i < ScreenPlaylistService.MAX_NETWORKS; i++) assertTrue(service.play("network/n" + i, owner, id, 0));
        assertFalse(service.play("network/overflow", owner, id, 0));
        service.removeNetwork("network/n0"); assertTrue(service.play("network/overflow", owner, id, 0));
        assertEquals(PlaybackSnapshot.EMPTY, service.snapshot("network/n0"));
    }
}
