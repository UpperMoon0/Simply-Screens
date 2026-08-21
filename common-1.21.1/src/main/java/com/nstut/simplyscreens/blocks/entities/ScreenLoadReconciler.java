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
            long blockPos
    ) {}

    private static final Set<Pending> PENDING =
            ConcurrentHashMap.newKeySet();

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
                pos.asLong()
        ));
    }

    private static void process(MinecraftServer server) {
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

            // Chunk disappeared before reconciliation.
            // If it loads again, setLevel() will enqueue it again.
            if (chunk == null) {
                continue;
            }

            BlockEntity be = chunk.getBlockEntity(pos);

            if (be instanceof ScreenBlockEntity screen
                    && !screen.isRemoved()) {
                screen.reconcileAfterLoad();
            }
        }
    }
}
