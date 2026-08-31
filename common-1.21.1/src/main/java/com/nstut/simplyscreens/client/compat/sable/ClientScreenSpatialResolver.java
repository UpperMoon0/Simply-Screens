package com.nstut.simplyscreens.client.compat.sable;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

/** Resolves screen visibility through Sable's interpolated client render pose. */
public final class ClientScreenSpatialResolver {
    private static final SableCompanion SABLE = SableCompanion.INSTANCE;
    private static final Map<VisibilityKey, Boolean> FRAME_VISIBILITY = new HashMap<>();

    private ClientScreenSpatialResolver() {}

    /** Starts a real world-render pass, discarding results from the previous pass. */
    public static void beginRenderFrame() {
        FRAME_VISIBILITY.clear();
    }

    /**
     * Tests a screen in render space when it belongs to a Sable sublevel.
     *
     * @return the Sable-aware result, or {@code null} when the screen is in the
     *         ordinary vanilla world and the caller should use its normal AABB test
     */
    public static @Nullable Boolean isWithinRenderDistance(ClientLevel level, BlockPos anchor,
                                                            BlockPos farCorner, int viewDistance) {
        return cachedVisibility(level, anchor, farCorner, viewDistance,
                () -> resolveVisibility(level, anchor, farCorner, viewDistance));
    }

    static @Nullable Boolean cachedVisibility(Object levelIdentity, BlockPos anchor, BlockPos farCorner,
                                               int viewDistance, Supplier<Boolean> computation) {
        VisibilityKey key = new VisibilityKey(levelIdentity, anchor, farCorner, viewDistance);
        if (FRAME_VISIBILITY.containsKey(key)) {
            return FRAME_VISIBILITY.get(key);
        }

        Boolean visibility = computation.get();
        FRAME_VISIBILITY.put(key, visibility);
        return visibility;
    }

    private static @Nullable Boolean resolveVisibility(ClientLevel level, BlockPos anchor,
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
        Pose3dc renderPose = clientSubLevel.renderPose();
        Vec3 renderCenter = renderPose.transformPosition(localCenter);
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double renderDistance = viewDistance + scaledRadius(radius, renderPose.scale());
        return camera.distanceToSqr(renderCenter) <= renderDistance * renderDistance;
    }

    static double scaledRadius(double radius, Vector3dc scale) {
        double maxScale = Math.max(Math.abs(scale.x()),
                Math.max(Math.abs(scale.y()), Math.abs(scale.z())));
        return radius * maxScale;
    }

    private record VisibilityKey(Object levelIdentity, BlockPos anchor, BlockPos farCorner, int viewDistance) {
        @Override
        public boolean equals(Object other) {
            return other instanceof VisibilityKey key
                    && levelIdentity == key.levelIdentity
                    && anchor.equals(key.anchor)
                    && farCorner.equals(key.farCorner)
                    && viewDistance == key.viewDistance;
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(levelIdentity);
            result = 31 * result + anchor.hashCode();
            result = 31 * result + farCorner.hashCode();
            return 31 * result + viewDistance;
        }
    }
}
