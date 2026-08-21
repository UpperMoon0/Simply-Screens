package com.nstut.simplyscreens.blocks.entities;

import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ScreenLoadReconciler {

    private record Pending(
            ResourceKey<Level> dimension,
            long blockPos,
            int attempts
    ) {}

    private static final Set<Pending> PENDING =
            ConcurrentHashMap.newKeySet();

    private static MinecraftServer activeServer;

    private static boolean initialized;

    private ScreenLoadReconciler() {}

    public static void init() {
        if (initialized) {
            return;
        }

        initialized = true;
        TickEvent.SERVER_POST.register(ScreenLoadReconciler::process);
    }

    public static void enqueue(ServerLevel level, BlockPos pos) {
        PENDING.add(new Pending(
                level.dimension(),
                pos.asLong(),
                0
        ));
    }

    private static void process(MinecraftServer server) {
        // Drop any entries from a previous server instance (e.g. an integrated
        // server that was stopped and restarted in the same JVM) so pending
        // work cannot leak into the next world.
        if (activeServer != null && activeServer != server) {
            PENDING.clear();
        }
        activeServer = server;

        if (PENDING.isEmpty()) {
            return;
        }

        Pending[] batch = PENDING.toArray(Pending[]::new);
        PENDING.removeAll(Arrays.asList(batch));

        for (Pending pending : batch) {
            ServerLevel level = server.getLevel(pending.dimension());
            if (level == null) {
                continue;
            }

            BlockPos pos = BlockPos.of(pending.blockPos());

            int chunkX = SectionPos.blockToSectionCoord(pos.getX());
            int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());

            LevelChunk chunk =
                    level.getChunkSource().getChunkNow(chunkX, chunkZ);

            // Chunk not yet exposed by getChunkNow(). Retry briefly, then drop
            // the entry so we never hold a permanent queue item for an
            // actually-unloaded chunk.
            if (chunk == null) {
                if (pending.attempts() < 40) {
                    PENDING.add(new Pending(
                            pending.dimension(),
                            pending.blockPos(),
                            pending.attempts() + 1
                    ));
                }
                continue;
            }

            BlockEntity be = chunk.getBlockEntity(pos);

            if (be instanceof ScreenBlockEntity screen
                    && !screen.isRemoved()) {
                screen.reconcileAfterLoad();
            }
        }
    }

    public static void clear() {
        PENDING.clear();
    }
}
