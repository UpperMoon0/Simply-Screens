package com.nstut.simplyscreens.client.compat.sable;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Resolves screen visibility through Sable's interpolated client render pose. */
public final class ClientScreenSpatialResolver {
    private static final SableCompanion SABLE = SableCompanion.INSTANCE;

    private ClientScreenSpatialResolver() {}

    /**
     * Tests a screen in render space when it belongs to a Sable sublevel.
     *
     * @return the Sable-aware result, or {@code null} when the screen is in the
     *         ordinary vanilla world and the caller should use its normal AABB test
     */
    public static @Nullable Boolean isWithinRenderDistance(ClientLevel level, BlockPos anchor,
                                                            BlockPos farCorner, int viewDistance) {
        SubLevelAccess subLevel = SABLE.getContaining(level, anchor);
        if (!(subLevel instanceof ClientSubLevelAccess clientSubLevel)) {
            return SABLE.isInPlotGrid(level, anchor) ? Boolean.FALSE : null;
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
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double renderDistance = viewDistance + radius;
        return camera.distanceToSqr(renderCenter) <= renderDistance * renderDistance;
    }
}
