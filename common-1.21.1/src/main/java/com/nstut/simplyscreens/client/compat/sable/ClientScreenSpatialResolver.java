package com.nstut.simplyscreens.client.compat.sable;

import com.nstut.simplyscreens.blocks.ScreenBlock;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;
import dev.architectury.platform.Platform;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
    private static final CullGeometryCache<ClientLevel> CULL_GEOMETRY = new CullGeometryCache<>();

    private ClientScreenSpatialResolver() {}

    /** Starts a real world-render pass, discarding results from the previous pass. */
    public static void beginRenderFrame() {
        FRAME_VISIBILITY.clearValues();
    }

    /** Releases level-scoped caches when the client leaves a world. */
    public static void clearCaches() {
        FRAME_VISIBILITY.clear();
        CULL_GEOMETRY.clear();
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

        Boolean visibility = resolveVisibility(level, screen, anchor, key, viewDistance);
        FRAME_VISIBILITY.put(level, key, visibility);
        return visibility;
    }

    private static @Nullable Boolean resolveVisibility(ClientLevel level, ScreenBlockEntity screen,
                                                        BlockPos anchor, long anchorKey, int viewDistance) {
        SubLevelAccess subLevel = SABLE.getContaining(level, anchor);
        if (!(subLevel instanceof ClientSubLevelAccess clientSubLevel)) {
            return SABLE.isInPlotGrid(level, anchor) ? Boolean.FALSE : null;
        }

        Direction facing = getFacing(screen);
        CullGeometry geometry = CULL_GEOMETRY.get(level, anchorKey, anchor,
                screen.getScreenWidth(), screen.getScreenHeight(), facing);
        Pose3dc renderPose = clientSubLevel.renderPose();
        Vec3 renderCenter = renderPose.transformPosition(geometry.localCenter());
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double renderDistance = viewDistance + scaledRadius(geometry.baseRadius(), renderPose.scale());
        return camera.distanceToSqr(renderCenter) <= renderDistance * renderDistance;
    }

    static double scaledRadius(double radius, Vector3dc scale) {
        double maxScale = Math.max(Math.abs(scale.x()),
                Math.max(Math.abs(scale.y()), Math.abs(scale.z())));
        return radius * maxScale;
    }

    private static Direction getFacing(ScreenBlockEntity screen) {
        return screen.getBlockState().hasProperty(ScreenBlock.FACING)
                ? screen.getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
    }

    private static Direction getWidthDirection(Direction facing) {
        return switch (facing) {
            case NORTH, UP, DOWN -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
        };
    }

    private static Direction getHeightDirection(Direction facing) {
        return facing.getAxis().isHorizontal()
                ? Direction.UP : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
    }

    static CullGeometry calculateGeometry(BlockPos anchor, int width, int height, Direction facing) {
        Direction widthDirection = getWidthDirection(facing);
        Direction heightDirection = getHeightDirection(facing);
        int widthOffset = Math.max(0, width - 1);
        int heightOffset = Math.max(0, height - 1);
        double farX = anchor.getX() + widthDirection.getStepX() * widthOffset
                + heightDirection.getStepX() * heightOffset;
        double farY = anchor.getY() + widthDirection.getStepY() * widthOffset
                + heightDirection.getStepY() * heightOffset;
        double farZ = anchor.getZ() + widthDirection.getStepZ() * widthOffset
                + heightDirection.getStepZ() * heightOffset;
        double minX = Math.min(anchor.getX(), farX);
        double minY = Math.min(anchor.getY(), farY);
        double minZ = Math.min(anchor.getZ(), farZ);
        double maxX = Math.max(anchor.getX(), farX) + 1.0;
        double maxY = Math.max(anchor.getY(), farY) + 1.0;
        double maxZ = Math.max(anchor.getZ(), farZ) + 1.0;
        Vec3 center = new Vec3((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5);
        double dx = maxX - center.x;
        double dy = maxY - center.y;
        double dz = maxZ - center.z;
        return new CullGeometry(width, height, facing, center, Math.sqrt(dx * dx + dy * dy + dz * dz));
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

        void clearValues() {
            levels.values().forEach(Long2ByteOpenHashMap::clear);
        }

        void clear() {
            levels.clear();
        }
    }

    static final class CullGeometryCache<L> {
        private final Map<L, Long2ObjectOpenHashMap<CullGeometry>> levels = new IdentityHashMap<>();

        CullGeometry get(L levelIdentity, long anchorKey, BlockPos anchor,
                         int width, int height, Direction facing) {
            Long2ObjectOpenHashMap<CullGeometry> levelGeometry =
                    levels.computeIfAbsent(levelIdentity, ignored -> new Long2ObjectOpenHashMap<>());
            CullGeometry cached = levelGeometry.get(anchorKey);
            if (cached != null && cached.matches(width, height, facing)) return cached;
            CullGeometry calculated = calculateGeometry(anchor, width, height, facing);
            levelGeometry.put(anchorKey, calculated);
            return calculated;
        }

        void clear() {
            levels.clear();
        }
    }

    record CullGeometry(int width, int height, Direction facing, Vec3 localCenter, double baseRadius) {
        boolean matches(int expectedWidth, int expectedHeight, Direction expectedFacing) {
            return width == expectedWidth && height == expectedHeight && facing == expectedFacing;
        }
    }

    private static final class SablePresence {
        private static final boolean LOADED = Platform.isModLoaded("sable");
    }
}
