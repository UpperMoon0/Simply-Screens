package com.nstut.simplyscreens.testing;

import java.util.concurrent.atomic.AtomicBoolean;

/** Shared state and markers for the opt-in real-client join smoke test. */
public final class LiveJoinTestProtocol {
    public static final String SYSTEM_PROPERTY = "simplyscreens.liveJoinTest";
    public static final String PASS_MARKER = "SIMPLYSCREENS_LIVE_JOIN_TEST_PASS";

    private static final AtomicBoolean DEFERRED = new AtomicBoolean();
    private static final AtomicBoolean COMPLETED = new AtomicBoolean();
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private LiveJoinTestProtocol() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(SYSTEM_PROPERTY);
    }

    public static void markDeferred() {
        DEFERRED.set(true);
    }

    public static void markCompleted() {
        COMPLETED.set(true);
    }

    public static boolean passed() {
        return DEFERRED.get() && COMPLETED.get();
    }

    public static void startProbe() {
        if (isEnabled()) {
            reset();
            markDeferred();
        }
    }

    public static boolean markReported() {
        return REPORTED.compareAndSet(false, true);
    }

    /** Require Minecraft's clean shutdown; a loader hang is a failed test. */
    public static void stopClient(Runnable cleanStop) {
        Thread fallback = new Thread(() -> waitForShutdown(15_000L, LiveJoinTestProtocol::failShutdown),
                "simplyscreens-live-test-exit");
        fallback.setDaemon(true);
        fallback.start();
        cleanStop.run();
    }

    static void waitForShutdown(long timeoutMillis, Runnable failure) {
        try { Thread.sleep(timeoutMillis); failure.run(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    static void failShutdown() {
        System.err.println("SIMPLYSCREENS_LIVE_JOIN_TEST_FAIL clean shutdown watchdog expired");
        System.exit(1);
    }

    public static void reset() {
        DEFERRED.set(false);
        COMPLETED.set(false);
        REPORTED.set(false);
    }
}
