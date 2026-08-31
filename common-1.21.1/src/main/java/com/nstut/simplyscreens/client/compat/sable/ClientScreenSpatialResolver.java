package com.nstut.simplyscreens.client.compat.sable;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** Resolves screen visibility through Sable's interpolated client render pose. */
public final class ClientScreenSpatialResolver {
    private static final SableCompanion SABLE = SableCompanion.INSTANCE;
    private static final Map<VisibilityKey, VisibilityResult> FRAME_VISIBILITY = new HashMap<>();
    private static ClientLevel cachedLevel;
    private static long cachedFrame = Long.MIN_VALUE;

    private ClientScreenSpatialResolver() {}

    /**
     * Tests a screen in render space when it belongs to a Sable sublevel.
     *
     * @return the Sable-aware result, or {@code null} when the screen is in the
     *         ordinary vanilla world and the caller should use its normal AABB test
     */
    public static @Nullable Boolean isWithinRenderDistance(ClientLevel level, BlockPos anchor,
                                                            BlockPos farCorner, int viewDistance) {
        Minecraft minecraft = Minecraft.getInstance();
        long frame = minecraft.getFrameTimeNs();
        if (frame != cachedFrame || level != cachedLevel) {
            FRAME_VISIBILITY.clear();
            cachedFrame = frame;
            cachedLevel = level;
        }

        VisibilityKey key = new VisibilityKey(anchor.immutable(), farCorner.immutable(), viewDistance);
        VisibilityResult result = FRAME_VISIBILITY.computeIfAbsent(key, ignored -> {
            SubLevelAccess subLevel = SABLE.getContaining(level, anchor);
            if (!(subLevel instanceof ClientSubLevelAccess clientSubLevel)) {
                return new VisibilityResult(SABLE.isInPlotGrid(level, anchor), false);
            }

            double minX = Math.min(anchor.getX(), farCorner.getX());
            double minY = Math.min(anchor.getY(), farCorner.getY());
            double minZ = Math.min(anchor.getZ(), farCorner.getZ());
            double maxX = Math.max(anchor.getX(), farCorner.getX()) + 1.0;
            double maxY = Math.max(anchor.getY(), farCorner.getY()) + 1.0;
            double maxZ = Math.max(anchor.getZ(), farCorner.getZ()) + 1.0;

            Vec3 localCenter = new Vec3(
                    (minX + maxX) * 0.5,
                    (minY + maxY) * 0.5,
                    (minZ + maxZ) * 0.5);
            double radius = localCenter.distanceTo(new Vec3(maxX, maxY, maxZ));
            Vec3 renderCenter = clientSubLevel.renderPose().transformPosition(localCenter);
            Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
            double renderDistance = viewDistance + radius;
            return new VisibilityResult(true,
                    camera.distanceToSqr(renderCenter) <= renderDistance * renderDistance);
        });
        return result.handled() ? result.visible() : null;
    }

    private record VisibilityKey(BlockPos anchor, BlockPos farCorner, int viewDistance) {}
    private record VisibilityResult(boolean handled, boolean visible) {}
}
