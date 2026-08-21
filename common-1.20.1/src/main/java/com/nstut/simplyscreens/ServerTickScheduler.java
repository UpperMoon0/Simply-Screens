package com.nstut.simplyscreens;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defers work to a real server tick boundary (TickEvent.SERVER_POST), which always
 * runs after world loading (prepareLevels) has completed. This avoids the misleading
 * semantics of MinecraftServer.execute(), which can run a task inline when called
 * from the server thread.
 *
 * Tasks are cleared automatically on server lifecycle events so pending work cannot
 * leak into the next world.
 */
public final class ServerTickScheduler {

    private static final Set<Runnable> TASKS = ConcurrentHashMap.newKeySet();

    private static MinecraftServer activeServer;

    private static boolean initialized;

    private ServerTickScheduler() {}

    public static void init() {
        if (initialized) {
            return;
        }

        initialized = true;

        LifecycleEvent.SERVER_BEFORE_START.register(server -> {
            TASKS.clear();
            activeServer = server;
        });

        LifecycleEvent.SERVER_STOPPED.register(server -> {
            if (activeServer == server) {
                TASKS.clear();
                activeServer = null;
            }
        });

        TickEvent.SERVER_POST.register(ServerTickScheduler::process);
    }

    public static void schedule(Runnable task) {
        if (task != null) {
            TASKS.add(task);
        }
    }

    private static void process(MinecraftServer server) {
        activeServer = server;

        if (TASKS.isEmpty()) {
            return;
        }

        Runnable[] batch = TASKS.toArray(Runnable[]::new);
        TASKS.removeAll(Arrays.asList(batch));

        for (Runnable task : batch) {
            try {
                task.run();
            } catch (Throwable t) {
                SimplyScreens.LOGGER.error("Error executing deferred server task", t);
            }
        }
    }

    public static void clear() {
        TASKS.clear();
    }
}
