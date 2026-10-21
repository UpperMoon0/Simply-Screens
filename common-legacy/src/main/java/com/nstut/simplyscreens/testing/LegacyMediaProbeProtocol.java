package com.nstut.simplyscreens.testing;

import java.util.Arrays;
import java.util.UUID;

/** Neutral Java 8 evidence gates. No client classes, transport, or gameplay authority. */
public final class LegacyMediaProbeProtocol {
    public static final boolean ENABLED = Boolean.getBoolean("simplyscreens.mediaSmokeTest");
    public static final long TIMEOUT_NANOS = 120_000_000_000L;
    public static final String SERVER_PASS = "SIMPLYSCREENS_LEGACY_MEDIA_SERVER_PASS";
    public static final String CLIENT_PASS = "SIMPLYSCREENS_LEGACY_MEDIA_CLIENT_PASS";
    public static final String FAIL = "SIMPLYSCREENS_LEGACY_MEDIA_FAIL";
    public static final int SIZE = 16;
    private LegacyMediaProbeProtocol() { }

    /** Native adapters supply identity, not substitute worlds or connection state. */
    public static void requireSession(Object expectedWorld, UUID expectedPlayer, long elapsedNanos,
                                      Object currentWorld, UUID currentPlayer) {
        if (elapsedNanos > TIMEOUT_NANOS)
            throw new IllegalStateException(FAIL + " client deadline waiting for media gates/cleanup");
        if (expectedWorld == null || currentWorld != expectedWorld || expectedPlayer == null || !expectedPlayer.equals(currentPlayer))
            throw new IllegalStateException(FAIL + " client/player world changed");
    }

    public static String imageName(UUID session) { return "legacy-media-" + session; }
    public static int argb(int x, int y) { return ((x / 4 + y / 4) & 1) == 0 ? 0xff12a4ed : 0xffef5831; }
    public static int abgr(int x, int y) { return argbToAbgr(argb(x, y)); }
    public static int argbToAbgr(int value) {
        return (value & 0xff00ff00) | ((value & 0xff) << 16) | ((value >>> 16) & 0xff);
    }
    // Owned 16x16 opaque checker PNG; embedded bytes are stable across server/client JDKs.
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAJ0lEQVR42mMQWvL2PzJ+H2GIggnJMwwDA0jVgC4/HAwYTQej6QCIAe9kjJ/uAjxqAAAAAElFTkSuQmCC");
    public static byte[] png() { return PNG.clone(); }

    /** A pass requires every gate for the same image, in the current probe session. */
    public static final class Evidence {
        private UUID image;
        private boolean server, download, texture, geometry, failed;
        public void server(UUID id) { identity(id); server = true; }
        public void download(UUID id, String extension, byte[] bytes) {
            identity(id);
            if (!"png".equals(extension) || !Arrays.equals(png(), bytes)) fail("download content mismatch");
            download = true;
        }
        public void texture(UUID id, int width, int height, boolean pixelsMatch, boolean registered) {
            identity(id);
            if (!download || width != SIZE || height != SIZE || !pixelsMatch || !registered)
                fail("native texture evidence mismatch");
            texture = true;
        }
        public void geometry(UUID id) {
            identity(id);
            if (!texture) fail("geometry preceded native texture verification");
            geometry = true;
        }
        public boolean passed() { return !failed && server && download && texture && geometry; }
        public boolean downloaded() { return !failed && download; }
        public boolean textured() { return !failed && texture; }
        public UUID image() { return image; }
        public void fail(String reason) { failed = true; throw new IllegalStateException(FAIL + " " + reason); }
        private void identity(UUID id) {
            if (failed) throw new IllegalStateException(FAIL + " probe already failed");
            if (id == null || image != null && !image.equals(id)) fail("image identity mismatch");
            image = id;
        }
    }
}
