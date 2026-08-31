package com.nstut.simplyscreens.client.compat.sable;

import com.nstut.simplyscreens.blocks.ScreenBlock;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import dev.architectury.platform.Platform;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

/** Resolves screen visibility through Sable's interpolated client render pose. */
public final class ClientScreenSpatialResolver {
    static final byte UNCACHED = 0;
    static final byte VANILLA = 1;
    static final byte HIDDEN = 2;
    static final byte VISIBLE = 3;

    private static final SableCompanion SABLE = SableCompanion.INSTANCE;
    private static final FrameVisibilityCache<ClientLevel> FRAME_VISIBILITY = new FrameVisibilityCache<>();

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
    public static @Nullable Boolean isWithinRenderDistance(ClientLevel level, ScreenBlockEntity screen,
                                                            BlockPos anchor, int viewDistance) {
        if (!SablePresence.LOADED) return null;

        long key = anchor.asLong();
        byte cached = FRAME_VISIBILITY.get(level, key);
        if (cached != UNCACHED) return decode(cached);

        Boolean visibility = resolveVisibility(level, screen, anchor, viewDistance);
        FRAME_VISIBILITY.put(level, key, visibility);
        return visibility;
    }

    private static @Nullable Boolean resolveVisibility(ClientLevel level, ScreenBlockEntity screen,
                                                        BlockPos anchor, int viewDistance) {
        SubLevelAccess subLevel = SABLE.getContaining(level, anchor);
        if (!(subLevel instanceof ClientSubLevelAccess clientSubLevel)) {
            return SABLE.isInPlotGrid(level, anchor) ? Boolean.FALSE : null;
        }

        BlockPos farCorner = getFarCorner(screen, anchor);
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

    private static BlockPos getFarCorner(ScreenBlockEntity screen, BlockPos anchor) {
        Direction facing = screen.getBlockState().hasProperty(ScreenBlock.FACING)
                ? screen.getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        Direction widthDirection = switch (facing) {
            case NORTH, UP, DOWN -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
        };
        Direction heightDirection = facing.getAxis().isHorizontal()
                ? Direction.UP : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
        return anchor.relative(widthDirection, screen.getScreenWidth() - 1)
                .relative(heightDirection, screen.getScreenHeight() - 1);
    }

    static @Nullable Boolean decode(byte state) {
        return switch (state) {
            case VANILLA -> null;
            case HIDDEN -> Boolean.FALSE;
            case VISIBLE -> Boolean.TRUE;
            default -> throw new IllegalArgumentException("Unknown visibility state: " + state);
        };
    }

    static final class FrameVisibilityCache<L> {
        private final Map<L, Long2ByteOpenHashMap> levels = new IdentityHashMap<>();

        byte get(L levelIdentity, long anchor) {
            Long2ByteOpenHashMap visibility = levels.get(levelIdentity);
            return visibility == null ? UNCACHED : visibility.get(anchor);
        }

        void put(L levelIdentity, long anchor, @Nullable Boolean visibility) {
            Long2ByteOpenHashMap levelVisibility = levels.computeIfAbsent(levelIdentity, ignored -> {
                Long2ByteOpenHashMap created = new Long2ByteOpenHashMap();
                created.defaultReturnValue(UNCACHED);
                return created;
            });
            levelVisibility.put(anchor, visibility == null ? VANILLA : visibility ? VISIBLE : HIDDEN);
        }

        void clear() {
            levels.clear();
        }
    }

    private static final class SablePresence {
        private static final boolean LOADED = Platform.isModLoaded("sable");
    }
}
