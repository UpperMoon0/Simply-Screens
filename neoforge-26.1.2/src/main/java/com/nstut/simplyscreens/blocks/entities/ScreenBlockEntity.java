package com.nstut.simplyscreens.blocks.entities;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.ScreenRegistryHelper.ScreenMetadata;
import com.nstut.simplyscreens.ScreenRegistry;
import com.nstut.simplyscreens.ScreenAnchorPromotion;
import com.nstut.simplyscreens.ScreenStructureDetector;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.ScreenBlock;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.UpdateScreenS2CPacket;
import net.minecraft.server.level.ServerPlayer;
import lombok.Getter;
import lombok.Setter;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

@Setter
@Getter
public class ScreenBlockEntity extends BlockEntity {
    private UUID imageId;
    private String screenId = ""; // User-defined ID for linking multiple screens to the same image
    private BlockPos anchorPos;
    private int screenWidth = 1;
    private int screenHeight = 1;
    private boolean maintainAspectRatio = true;
    private int tickSinceLastUpdate = 0;
    private boolean screenLinkRegistered;
    private boolean needsStructureRefresh = true;
    private final Set<BlockPos> allowedMergeAnchors = new HashSet<>();

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityRegistries.SCREEN.get(), pos, state);
        this.anchorPos = pos;
        
    }

    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        if (!level.isClientSide()) com.nstut.simplyscreens.helpers.ServerImageManager.trackLoadedScreen(this);

        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            ScreenLoadReconciler.enqueue(serverLevel, worldPosition);
        }
    }

    public static final int MAX_SCREEN_DIMENSION = 64;

    void reconcileAfterLoad() {
        if (level == null || level.isClientSide() || isRemoved()) return;
        if (isAnchor()) {
            if ((screenId == null || screenId.isEmpty()) && imageId != null
                    && com.nstut.simplyscreens.helpers.ServerImageManager.getImageMetadata(level.getServer(), imageId) == null) {
                setImageId(null);
            }
            needsStructureRefresh = true;
            synchronizeLoadedChildren();
            return;
        }
        if (anchorPos == null) return;
        BlockPos currentAnchorPos = anchorPos;
        ScreenBlockEntity anchor = getLoadedAnchor(currentAnchorPos);
        if (anchor == null) {
            BlockPos redirected = ScreenRegistry.resolveAnchorRedirect(level, currentAnchorPos);
            if (redirected != null) {
                this.anchorPos = redirected;
                setChanged();
                anchor = getLoadedAnchor(redirected);
            }
        }
        if (anchor != null) {
            Direction facing = anchor.getFacing();
            if (isInsideRectangle(worldPosition, anchor.worldPosition, facing, anchor.screenWidth, anchor.screenHeight)) {
                updateScreen(anchor.imageId, anchor.screenWidth, anchor.screenHeight, anchor.worldPosition, anchor.maintainAspectRatio);
                setScreenIdInternal(anchor.screenId);
                anchor.needsStructureRefresh = true;
            } else {
                this.anchorPos = worldPosition;
                this.screenWidth = 1;
                this.screenHeight = 1;
                this.needsStructureRefresh = true;
                setChanged();
                updateScreenStructure();
            }
        }
    }

    private ScreenBlockEntity getLoadedAnchor(BlockPos pos) {
        if (pos == null || level == null) return null;
        BlockEntity be = getLoadedBlockEntity(pos);
        if (be instanceof ScreenBlockEntity anchor && anchor.isAnchor()) {
            return anchor;
        }
        return null;
    }

    private void synchronizeLoadedChildren() {
        Direction facing = getFacing();
        Direction widthDirection = getWidthDirection(facing);
        Direction heightDirection = getHeightDirection(facing);
        for (int width = 0; width < screenWidth; width++) {
            for (int height = 0; height < screenHeight; height++) {
                BlockPos pos = worldPosition.relative(widthDirection, width).relative(heightDirection, height);
                if (getLoadedBlockEntity(pos) instanceof ScreenBlockEntity screen) {
                    screen.updateScreen(imageId, screenWidth, screenHeight, worldPosition, maintainAspectRatio);
                    screen.setScreenIdInternal(screenId);
                }
            }
        }
    }

    @Override
    public void setRemoved() {
        com.nstut.simplyscreens.helpers.ServerImageManager.untrackLoadedScreen(this);
        if (level != null && !level.isClientSide() && isAnchor()) {
            ScreenRegistry.unregisterScreen(level, worldPosition, screenId);
        }
        if (level != null && !level.isClientSide() && level.getServer() != null) {
            Direction facing = getBlockState().getValue(ScreenBlock.FACING);
            level.getServer().execute(() -> ScreenBlock.refreshScreens(level, worldPosition, facing));
        }
        super.setRemoved();
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        // 26.1.2 invokes this for every server-side state replacement while this
        // block entity is still available, covering players, explosions, and pistons.
        if (isAnchor()) {
            findNewAnchor();
        }
        super.preRemoveSideEffects(pos, state);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        writePersistentData(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        readPersistentData(input);
    }

    private void writePersistentData(ValueOutput tag) {
        if (imageId != null) {
            tag.putString("imageId", imageId.toString());
        }
        if (screenId != null && !screenId.isEmpty()) {
            tag.putString("screenId", screenId);
        }
        tag.putBoolean("maintainAspectRatio", maintainAspectRatio);
        tag.putInt("screenWidth", screenWidth);
        tag.putInt("screenHeight", screenHeight);

        if (anchorPos != null) {
            tag.putInt("anchorX", anchorPos.getX());
            tag.putInt("anchorY", anchorPos.getY());
            tag.putInt("anchorZ", anchorPos.getZ());
        }
    }

    private void readPersistentData(ValueInput tag) {
        imageId = tag.getString("imageId").map(value -> {
            try { return UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
        }).orElse(null);
        screenId = com.nstut.simplyscreens.ScreenRegistryHelper.normalizeScreenId(tag.getStringOr("screenId", ""));
        maintainAspectRatio = tag.getBooleanOr("maintainAspectRatio", true);
        screenWidth = Math.max(1, Math.min(64, tag.getIntOr("screenWidth", 1)));
        screenHeight = Math.max(1, Math.min(64, tag.getIntOr("screenHeight", 1)));

        if (tag.getInt("anchorX").isPresent() && tag.getInt("anchorY").isPresent() && tag.getInt("anchorZ").isPresent()) {
            anchorPos = new BlockPos(
                    tag.getIntOr("anchorX", 0),
                    tag.getIntOr("anchorY", 0),
                    tag.getIntOr("anchorZ", 0)
            );
        }
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveWithoutMetadata(provider);
    }

    private void updateClients() {
        if (level != null && !level.isClientSide()) {
            UUID resolvedImageId = getResolvedImageId();
            UpdateScreenS2CPacket packet = new UpdateScreenS2CPacket(worldPosition, anchorPos, resolvedImageId, maintainAspectRatio, screenId, screenWidth, screenHeight);
            if (level instanceof ServerLevel serverLevel) {
                for (ServerPlayer player : serverLevel.getChunkSource().chunkMap.getPlayers(new ChunkPos(worldPosition.getX() >> 4, worldPosition.getZ() >> 4), false)) {
                    PacketRegistries.sendToPlayer(player, packet);
                }
            }
        }
    }

    public void setImageId(UUID imageId) {
        if (level != null && level.isClientSide()) {
            this.imageId = imageId;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            return;
        }

        if (level == null) return;

        ScreenBlockEntity anchor = getAnchorEntity();
        if (anchor != null) {
            anchor.forceImageId(imageId);
        } else {
            switchToErrorState();
        }
    }

    public void setScreenId(String screenId) {
        if (level != null && level.isClientSide()) {
            this.screenId = com.nstut.simplyscreens.ScreenRegistryHelper.normalizeScreenId(screenId);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            return;
        }

        if (level == null) return;

        ScreenBlockEntity anchor = getAnchorEntity();
        if (anchor != null) {
            anchor.forceScreenId(screenId);
        } else {
            switchToErrorState();
        }
    }

    /**
     * Gets the resolved image ID - if screenId is set, look it up from registry,
     * otherwise return the direct imageId.
     * @return The resolved image UUID
     */
    public UUID getResolvedImageId() {
        if (level != null && level.isClientSide()) {
            return imageId;
        }
        if (screenId != null && !screenId.isEmpty()) {
            return ScreenRegistry.getImageId(screenId);
        }
        return imageId;
    }

    public void setMaintainAspectRatio(boolean maintainAspectRatio) {
        if (level != null && level.isClientSide()) {
            this.maintainAspectRatio = maintainAspectRatio;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            return;
        }

        if (level == null) return;

        ScreenBlockEntity anchor = getAnchorEntity();
        if (anchor != null) {
            anchor.forceMaintainAspectRatio(maintainAspectRatio);
        } else {
            switchToErrorState();
        }
    }

    public void forceMaintainAspectRatio(boolean maintainAspectRatio) {
        if (level == null || level.isClientSide() || !isAnchor()) {
            return;
        }

        this.maintainAspectRatio = maintainAspectRatio;
        setChanged();

        Direction facing = getBlockState().hasProperty(ScreenBlock.FACING) ?
            getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        Direction widthDirection = getWidthDirection(facing);
        Direction heightDirection = getHeightDirection(facing);

        for (int w = 0; w < screenWidth; w++) {
            for (int h = 0; h < screenHeight; h++) {
                BlockPos currentPos = worldPosition.relative(widthDirection, w).relative(heightDirection, h);
                BlockEntity be = getLoadedBlockEntity(currentPos);
                if (be instanceof ScreenBlockEntity screen) {
                    screen.updateScreen(this.imageId, this.screenWidth, this.screenHeight, this.worldPosition, this.maintainAspectRatio);
                }
            }
        }
    }

    public ScreenBlockEntity getAnchorEntity() {
        if (level == null) return null;
        if (isAnchor()) {
            return this;
        }
        if (anchorPos != null) {
            ScreenBlockEntity anchor = getLoadedAnchor(anchorPos);
            if (anchor != null) {
                return anchor;
            }
            BlockPos redirected = ScreenRegistry.resolveAnchorRedirect(level, anchorPos);
            if (redirected != null) {
                this.anchorPos = redirected;
                setChanged();
                return getLoadedAnchor(redirected);
            }
        }
        return null;
    }

    public void forceImageId(UUID imageId) {
        if (level == null || level.isClientSide() || !isAnchor()) {
            return;
        }

        this.imageId = imageId;
        setChanged();

        Direction facing = getBlockState().hasProperty(ScreenBlock.FACING) ?
            getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        Direction widthDirection = getWidthDirection(facing);
        Direction heightDirection = getHeightDirection(facing);

        for (int w = 0; w < screenWidth; w++) {
            for (int h = 0; h < screenHeight; h++) {
                BlockPos currentPos = worldPosition.relative(widthDirection, w).relative(heightDirection, h);
                BlockEntity be = getLoadedBlockEntity(currentPos);
                if (be instanceof ScreenBlockEntity screen) {
                    screen.updateScreen(this.imageId, this.screenWidth, this.screenHeight, this.worldPosition, this.maintainAspectRatio);
                }
            }
        }

        if (screenId != null && !screenId.isEmpty()) {
            ScreenRegistry.setImageId(screenId, imageId);
            ScreenRegistry.saveRegistry();
            broadcastImageIdToLinkedScreens(imageId);
        }
    }

    private void broadcastImageIdToLinkedScreens(UUID linkedImageId) {
        if (level == null || screenId == null || screenId.isEmpty()) return;

        if (level instanceof ServerLevel serverLevel && serverLevel.getServer() != null) {
            for (ServerLevel lvl : serverLevel.getServer().getAllLevels()) {
                for (BlockPos pos : ScreenRegistry.getPositionsForScreenId(lvl, screenId)) {
                    if (lvl == level && pos.equals(worldPosition)) continue;
                    if (!lvl.hasChunkAt(pos)) continue;
                    BlockEntity be = lvl.getBlockEntity(pos);
                    if (be instanceof ScreenBlockEntity linkedScreen && linkedScreen.isAnchor() && screenId.equals(linkedScreen.getScreenId())) {
                        linkedScreen.applyLinkedImageId(linkedImageId);
                    }
                }
            }
        } else {
            for (BlockPos pos : ScreenRegistry.getPositionsForScreenId(level, screenId)) {
                if (pos.equals(worldPosition)) continue;
                BlockEntity be = getLoadedBlockEntity(pos);
                if (be instanceof ScreenBlockEntity linkedScreen && linkedScreen.isAnchor() && screenId.equals(linkedScreen.getScreenId())) {
                    linkedScreen.applyLinkedImageId(linkedImageId);
                }
            }
        }
    }

    private void applyLinkedImageId(UUID linkedImageId) {
        this.imageId = linkedImageId;
        setChanged();
        Direction facing = getBlockState().hasProperty(ScreenBlock.FACING)
                ? getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        Direction widthDirection = getWidthDirection(facing);
        Direction heightDirection = getHeightDirection(facing);
        for (int w = 0; w < screenWidth; w++) {
            for (int h = 0; h < screenHeight; h++) {
                BlockEntity blockEntity = getLoadedBlockEntity(worldPosition.relative(widthDirection, w).relative(heightDirection, h));
                if (blockEntity instanceof ScreenBlockEntity screen) {
                    screen.updateScreen(linkedImageId, screenWidth, screenHeight, worldPosition, maintainAspectRatio);
                }
            }
        }
        updateClients();
    }

    public void forceScreenId(String screenId) {
        if (level == null || level.isClientSide() || !isAnchor()) {
            return;
        }

        String oldScreenId = this.screenId;
        this.screenId = com.nstut.simplyscreens.ScreenRegistryHelper.normalizeScreenId(screenId);
        UUID linkedImage = this.screenId.isEmpty() ? null : ScreenRegistry.getImageId(this.screenId);
        if (linkedImage != null) {
            this.imageId = linkedImage;
        } else if (!this.screenId.isEmpty() && this.imageId != null) {
            ScreenRegistry.setImageId(this.screenId, this.imageId);
            ScreenRegistry.saveRegistry();
        }
        setChanged();

        // Update registry
        if (oldScreenId != null && !oldScreenId.isEmpty()) {
            ScreenRegistry.updateScreenId(level, worldPosition, oldScreenId, this.screenId);
        } else if (!this.screenId.isEmpty()) {
            ScreenRegistry.registerScreen(level, worldPosition, this.screenId);
        }
        screenLinkRegistered = !this.screenId.isEmpty();

        Direction facing = getBlockState().hasProperty(ScreenBlock.FACING) ?
            getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        Direction widthDirection = getWidthDirection(facing);
        Direction heightDirection = getHeightDirection(facing);

        for (int w = 0; w < screenWidth; w++) {
            for (int h = 0; h < screenHeight; h++) {
                BlockPos currentPos = worldPosition.relative(widthDirection, w).relative(heightDirection, h);
                BlockEntity be = getLoadedBlockEntity(currentPos);
                if (be instanceof ScreenBlockEntity screen) {
                    screen.updateScreen(this.imageId, this.screenWidth, this.screenHeight, this.worldPosition, this.maintainAspectRatio);
                    screen.setScreenIdInternal(this.screenId);
                }
            }
        }
    }

    private void setScreenIdInternal(String screenId) {
        this.screenId = com.nstut.simplyscreens.ScreenRegistryHelper.normalizeScreenId(screenId);
    }

    public void updateScreen(UUID imageId, int width, int height, BlockPos anchor, boolean maintainAspect) {
        if (level == null || level.isClientSide()) return;

        this.imageId = imageId;
        this.screenWidth = width;
        this.screenHeight = height;
        this.anchorPos = anchor;
        this.maintainAspectRatio = maintainAspect;
        setChanged();

        updateClients();

        if (getBlockState().getBlock() instanceof ScreenBlock) {
            BlockState currentState = getBlockState();
            BlockState newState = currentState.setValue(
                    ScreenBlock.STATE,
                    isAnchor() ? ScreenBlock.STATE_ANCHOR : ScreenBlock.STATE_CHILD
            );
            if (!currentState.equals(newState)) {
                level.setBlock(worldPosition, newState, Block.UPDATE_ALL);
            }
        }
    }

    public void tick() {
        if (level == null || level.isClientSide()) return;

        if (isAnchor()) {
            if (needsStructureRefresh && ++tickSinceLastUpdate >= Config.SCREEN_TICK_RATE) {
                tickSinceLastUpdate = 0;
                updateScreenStructure();
            }
            if (!screenLinkRegistered && screenId != null && !screenId.isEmpty()) {
                ScreenRegistry.registerScreen(level, worldPosition, screenId);
                screenLinkRegistered = true;
                UUID registryImage = ScreenRegistry.getImageId(screenId);
                if (!java.util.Objects.equals(registryImage, imageId)) {
                    applyLinkedImageId(registryImage);
                }
            }
        }
    }

    public void updateScreenStructure() {
        Direction facing = getFacing();
        if (!isCurrentStructureLoaded(facing)) {
            needsStructureRefresh = true;
            return;
        }
        int oldWidth = this.screenWidth;
        int oldHeight = this.screenHeight;
        BlockPos oldAnchor = this.anchorPos != null ? this.anchorPos : this.worldPosition;

        BlockPos farCorner = calculateStructureBounds(facing);

        if (farCorner != null) {
            needsStructureRefresh = false;
            calculateScreenDimensions(facing, farCorner);
            int newWidth = this.screenWidth;
            int newHeight = this.screenHeight;

            Direction widthDirection = getWidthDirection(facing);
            Direction heightDirection = getHeightDirection(facing);

            boolean currentHasMetadata = this.imageId != null || (this.screenId != null && !this.screenId.isEmpty());
            ScreenMetadata childMetadata = null;

            for (int w = 0; w < newWidth; w++) {
                for (int h = 0; h < newHeight; h++) {
                    BlockPos pos = worldPosition.relative(widthDirection, w).relative(heightDirection, h);
                    if (pos.equals(worldPosition)) continue;
                    BlockEntity be = getLoadedBlockEntity(pos);
                    if (be instanceof ScreenBlockEntity other && other.isAnchor()) {
                        if (childMetadata == null && (other.imageId != null || other.screenId != null && !other.screenId.isEmpty())) {
                            childMetadata = new ScreenMetadata(other.imageId, other.screenId, other.maintainAspectRatio);
                        }
                        ScreenRegistry.unregisterScreen(level, other.worldPosition, other.screenId);
                        other.screenLinkRegistered = false;
                    }
                }
            }

            if (!currentHasMetadata && childMetadata != null) {
                this.imageId = childMetadata.imageId();
                this.screenId = childMetadata.screenId();
                this.maintainAspectRatio = childMetadata.maintainAspectRatio();
            }

            if (this.screenId != null && !this.screenId.isEmpty()) {
                ScreenRegistry.registerScreen(level, worldPosition, this.screenId);
                screenLinkRegistered = true;
                UUID registryImage = ScreenRegistry.getImageId(this.screenId);
                if (!java.util.Objects.equals(registryImage, this.imageId)) {
                    this.imageId = registryImage;
                }
            }

            this.updateScreen(this.imageId, newWidth, newHeight, worldPosition, this.maintainAspectRatio);
            this.setScreenIdInternal(this.screenId);

            for (int w = 0; w < newWidth; w++) {
                for (int h = 0; h < newHeight; h++) {
                    BlockPos pos = worldPosition.relative(widthDirection, w).relative(heightDirection, h);
                    if (pos.equals(worldPosition)) continue;
                    BlockEntity be = getLoadedBlockEntity(pos);
                    if (be instanceof ScreenBlockEntity child) {
                        child.updateScreen(this.imageId, newWidth, newHeight, worldPosition, this.maintainAspectRatio);
                        child.setScreenIdInternal(this.screenId);
                    }
                }
            }

            if (oldAnchor.equals(worldPosition)) {
                for (int w = 0; w < oldWidth; w++) {
                    for (int h = 0; h < oldHeight; h++) {
                        if (w < newWidth && h < newHeight) continue;
                        BlockPos leftoverPos = worldPosition.relative(widthDirection, w).relative(heightDirection, h);
                        if (!level.hasChunkAt(leftoverPos)) continue;
                        BlockEntity be = getLoadedBlockEntity(leftoverPos);
                        if (be instanceof ScreenBlockEntity leftover && !leftover.isAnchor() && worldPosition.equals(leftover.anchorPos)) {
                            leftover.anchorPos = leftoverPos;
                            leftover.imageId = this.imageId;
                            leftover.screenId = this.screenId;
                            leftover.maintainAspectRatio = this.maintainAspectRatio;
                            leftover.screenWidth = 1;
                            leftover.screenHeight = 1;
                            leftover.needsStructureRefresh = true;
                            leftover.screenLinkRegistered = false;
                            leftover.setChanged();
                            level.setBlock(leftoverPos, leftover.getBlockState().setValue(ScreenBlock.STATE, ScreenBlock.STATE_ANCHOR), Block.UPDATE_ALL);
                        }
                    }
                }
            }
        } else {
            needsStructureRefresh = true;
        }
    }

    public void markForRenderUpdate() {
        if (level != null && !level.isClientSide()) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
        }
    }

    private void calculateScreenDimensions(Direction facing, BlockPos farCorner) {
        if (isHorizontal(facing)) {
            screenWidth = facing.getAxis() == Direction.Axis.Z ?
                    Math.abs(farCorner.getX() - worldPosition.getX()) + 1 :
                    Math.abs(farCorner.getZ() - worldPosition.getZ()) + 1;
            screenHeight = farCorner.getY() - worldPosition.getY() + 1;
        } else {
            screenWidth = Math.abs(farCorner.getX() - worldPosition.getX()) + 1;
            screenHeight = Math.abs(farCorner.getZ() - worldPosition.getZ()) + 1;
        }
    }

    private BlockPos calculateStructureBounds(Direction facing) {
        if (level == null || level.isClientSide()) return null;
        try {
            allowedMergeAnchors.clear();
            Direction widthDirection = getWidthDirection(facing);
            Direction heightDirection = getHeightDirection(facing);
            int maxWidth = findMaxExtension(widthDirection, facing);
            int maxHeight = findMaxExtension(heightDirection, facing);

            ScreenStructureDetector.Bounds bounds = ScreenStructureDetector.detect(maxWidth, maxHeight, (width, height) -> {
                BlockPos checkPos = worldPosition.relative(widthDirection, width).relative(heightDirection, height);
                return isMatchingScreen(checkPos, facing);
            });
            return worldPosition.relative(widthDirection, bounds.width()).relative(heightDirection, bounds.height());
        } catch (UnresolvedStructureException ignored) {
            return null;
        }
    }

    private boolean isCurrentStructureLoaded(Direction facing) {
        Direction widthDirection = getWidthDirection(facing);
        Direction heightDirection = getHeightDirection(facing);
        for (int width = 0; width < screenWidth; width++) {
            for (int height = 0; height < screenHeight; height++) {
                if (!isChunkFullyAvailable(worldPosition.relative(widthDirection, width).relative(heightDirection, height))) return false;
            }
        }
        return true;
    }

    private boolean isChunkFullyAvailable(BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return level != null && level.hasChunkAt(pos);
        }

        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());

        return serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }

    private int findMaxExtension(Direction direction, Direction facing) {
        if (level == null) return 0;

        int extension = 0;
        BlockPos current = worldPosition.relative(direction);

        while (extension < MAX_SCREEN_DIMENSION - 1 && isMatchingScreen(current, facing)) {
            extension++;
            current = current.relative(direction);
        }

        return extension;
    }

    private boolean isMatchingScreen(BlockPos pos, Direction facing) {
        if (!isChunkFullyAvailable(pos)) throw new UnresolvedStructureException();
        BlockEntity entity = getLoadedBlockEntity(pos);
        if (!(entity instanceof ScreenBlockEntity screen) || !entity.getBlockState().hasProperty(ScreenBlock.FACING)
                || entity.getBlockState().getValue(ScreenBlock.FACING) != facing) return false;
        BlockPos foreignAnchor = screen.getAnchorPos();
        if (screen.isAnchor() && !screen.getBlockPos().equals(this.worldPosition)) {
            allowedMergeAnchors.add(screen.getBlockPos());
        }
        if (foreignAnchor != null && !foreignAnchor.equals(screen.getBlockPos()) && !foreignAnchor.equals(this.worldPosition)) {
            if (allowedMergeAnchors.contains(foreignAnchor)) return true;
            if (isChunkFullyAvailable(foreignAnchor)) {
                BlockEntity foreignBe = getLoadedBlockEntity(foreignAnchor);
                if (foreignBe instanceof ScreenBlockEntity foreignAnchorBe && foreignAnchorBe.isAnchor()) {
                    return false;
                }
            } else {
                BlockPos redirected = ScreenRegistry.resolveAnchorRedirect(level, foreignAnchor);
                if (redirected != null && redirected.equals(this.worldPosition)) {
                    screen.anchorPos = this.worldPosition;
                    screen.setChanged();
                    return true;
                }
                throw new UnresolvedStructureException();
            }
        }
        return true;
    }

    private static final class UnresolvedStructureException extends RuntimeException { }

    private void verifyAnchorValidity() {
        if (anchorPos == null || level == null) return;
        if (!level.hasChunkAt(anchorPos)) return;

        BlockEntity be = getLoadedBlockEntity(anchorPos);
        if (!(be instanceof ScreenBlockEntity anchorEntity) || !anchorEntity.isAnchor()) {
            BlockPos redirected = ScreenRegistry.resolveAnchorRedirect(level, anchorPos);
            if (redirected != null) {
                this.anchorPos = redirected;
                setChanged();
                return;
            }
            switchToErrorState();
        }
    }

    private void switchToErrorState() {
        if (level == null) return;

        imageId = null;
        anchorPos = null;
        screenWidth = 1;
        screenHeight = 1;

        if (getBlockState().getBlock() instanceof ScreenBlock) {
            BlockState errorState = getBlockState()
                    .setValue(ScreenBlock.STATE, ScreenBlock.STATE_ERROR);
            level.setBlock(worldPosition, errorState, Block.UPDATE_ALL);
        }
    }

    public boolean isAnchor() {
        return anchorPos != null && anchorPos.equals(worldPosition);
    }

    private BlockEntity getLoadedBlockEntity(BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }

        if (level instanceof ServerLevel serverLevel) {
            int chunkX = SectionPos.blockToSectionCoord(pos.getX());
            int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());

            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);

            if (chunk == null) {
                return null;
            }

            return chunk.getBlockEntity(pos);
        }

        // Client side only. The server-side deadlock path cannot
        // come through here.
        if (!level.hasChunkAt(pos)) {
            return null;
        }

        return level.getBlockEntity(pos);
    }

    public AABB getRenderBoundingBox() {
        BlockPos anchor = anchorPos != null ? anchorPos : worldPosition;
        Direction facing = getFacing();
        BlockPos far = anchor.relative(getWidthDirection(facing), Math.max(0, screenWidth - 1))
                .relative(getHeightDirection(facing), Math.max(0, screenHeight - 1));
        return new AABB(
                Math.min(anchor.getX(), far.getX()), Math.min(anchor.getY(), far.getY()), Math.min(anchor.getZ(), far.getZ()),
                Math.max(anchor.getX(), far.getX()) + 1, Math.max(anchor.getY(), far.getY()) + 1, Math.max(anchor.getZ(), far.getZ()) + 1);
    }

    private static Direction getWidthDirection(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
            case UP, DOWN -> Direction.WEST;
        };
    }

    public Direction getFacing() {
        return getBlockState().hasProperty(ScreenBlock.FACING)
                ? getBlockState().getValue(ScreenBlock.FACING)
                : Direction.NORTH;
    }

    private static boolean isHorizontal(Direction facing) {
        return facing.getAxis().isHorizontal();
    }

    private static Direction getHeightDirection(Direction facing) {
        return isHorizontal(facing) ? Direction.UP : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
    }

    private static boolean isInsideRectangle(BlockPos pos, BlockPos origin, Direction facing, int width, int height) {
        Direction widthDir = getWidthDirection(facing);
        Direction heightDir = getHeightDirection(facing);
        for (int w = 0; w < width; w++) {
            for (int h = 0; h < height; h++) {
                if (pos.equals(origin.relative(widthDir, w).relative(heightDir, h))) return true;
            }
        }
        return false;
    }

    public void findNewAnchor() {
        if (level == null || level.isClientSide()) return;

        ScreenAnchorPromotion.Result promotion = ScreenAnchorPromotion.choose(screenWidth, screenHeight);
        Direction facing = getFacing();
        Direction widthDirection = getWidthDirection(facing);
        Direction heightDirection = getHeightDirection(facing);
        int oldWidth = this.screenWidth;
        int oldHeight = this.screenHeight;

        BlockPos newAnchorPos = null;
        if (promotion.axis() != ScreenAnchorPromotion.Axis.NONE) {
            newAnchorPos = worldPosition.relative(promotion.axis() == ScreenAnchorPromotion.Axis.HEIGHT
                    ? getHeightDirection(facing) : getWidthDirection(facing));
            if (!level.hasChunkAt(newAnchorPos)) level.getChunkAt(newAnchorPos);
        }

        // Load the complete bounded footprint before creating the redirect. Every
        // preserved child must be rewritten while the old anchor still exists.
        for (int w = 0; w < oldWidth; w++) {
            for (int h = 0; h < oldHeight; h++) {
                BlockPos pos = worldPosition.relative(widthDirection, w).relative(heightDirection, h);
                if (!level.hasChunkAt(pos)) level.getChunkAt(pos);
            }
        }

        BlockEntity newAnchorBe = newAnchorPos != null ? getLoadedBlockEntity(newAnchorPos) : null;
        if (newAnchorBe instanceof ScreenBlockEntity newAnchor) {
            ScreenRegistry.redirectAnchor(level, worldPosition, newAnchorPos);
            newAnchor.updateScreen(this.imageId, promotion.width(), promotion.height(), newAnchorPos, this.maintainAspectRatio);
            newAnchor.setScreenIdInternal(this.screenId);
            newAnchor.screenLinkRegistered = false;
            if (this.screenId != null && !this.screenId.isEmpty()) {
                ScreenRegistry.registerScreen(level, newAnchorPos, this.screenId);
                newAnchor.screenLinkRegistered = true;
            }
            updateChildrenToNewAnchor(newAnchorPos, facing, promotion.width(), promotion.height());
            newAnchor.updateScreenStructure();
            newAnchor.markForRenderUpdate();

            if (level instanceof ServerLevel serverLevel) {
                for (ServerPlayer player : serverLevel.getChunkSource().chunkMap.getPlayers(new ChunkPos(newAnchorPos.getX() >> 4, newAnchorPos.getZ() >> 4), false)) {
                    PacketRegistries.sendToPlayer(player, new UpdateScreenS2CPacket(
                            newAnchorPos, newAnchorPos, imageId, maintainAspectRatio, screenId,
                            promotion.width(), promotion.height()));
                }
            }
        }

        for (int w = 0; w < oldWidth; w++) {
            for (int h = 0; h < oldHeight; h++) {
                BlockPos pos = worldPosition.relative(widthDirection, w).relative(heightDirection, h);
                if (pos.equals(worldPosition)) continue;
                if (!level.hasChunkAt(pos)) continue;
                BlockEntity be = getLoadedBlockEntity(pos);
                if (be instanceof ScreenBlockEntity leftover) {
                    if (newAnchorPos != null && isInsideRectangle(pos, newAnchorPos, facing, promotion.width(), promotion.height())) {
                        if (!newAnchorPos.equals(leftover.anchorPos)) {
                            leftover.updateScreen(this.imageId, promotion.width(), promotion.height(), newAnchorPos, this.maintainAspectRatio);
                            leftover.setScreenIdInternal(this.screenId);
                        }
                        continue;
                    }
                    leftover.anchorPos = pos;
                    leftover.imageId = this.imageId;
                    leftover.screenId = this.screenId;
                    leftover.maintainAspectRatio = this.maintainAspectRatio;
                    leftover.screenWidth = 1;
                    leftover.screenHeight = 1;
                    leftover.needsStructureRefresh = true;
                    leftover.screenLinkRegistered = false;
                    leftover.setChanged();
                    level.setBlock(pos, leftover.getBlockState().setValue(ScreenBlock.STATE, ScreenBlock.STATE_ANCHOR), Block.UPDATE_ALL);
                }
            }
        }
        ScreenRegistry.removeAnchorRedirect(level, worldPosition);
    }

    private void updateChildrenToNewAnchor(BlockPos newAnchorPos, Direction facing, int width, int height) {
        if (level == null || level.isClientSide()) return;

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                BlockPos childPos = calculateChildPosition(newAnchorPos, facing, x, y);
                if (childPos.equals(newAnchorPos)) continue;

                BlockEntity be = getLoadedBlockEntity(childPos);
                if (be instanceof ScreenBlockEntity child) {
                    child.updateScreen(this.imageId, width, height, newAnchorPos, this.maintainAspectRatio);
                    child.setScreenIdInternal(this.screenId);
                    child.markForRenderUpdate();
                }
            }
        }
    }

    private BlockPos calculateChildPosition(BlockPos origin, Direction facing, int x, int y) {
        Direction widthDirection = getWidthDirection(facing);
        Direction heightDirection = getHeightDirection(facing);
        return origin.relative(widthDirection, x).relative(heightDirection, y);
    }

    public void onNeighborRemoved() {
        if (level == null || level.isClientSide() || anchorPos == null) return;

        if (anchorPos.equals(worldPosition)) {
            updateScreenStructure();
        } else {
            if (!level.hasChunkAt(anchorPos)) return;
            BlockEntity anchorBe = getLoadedBlockEntity(anchorPos);
            if (anchorBe instanceof ScreenBlockEntity anchor) {
                anchor.updateScreenStructure();
            }
        }
    }

    public void onNeighborPlaced(BlockPos neighborPos, Direction neighborDir) {
        if (level == null || level.isClientSide() || anchorPos == null) return;

        Direction facing = getFacing();

        if (anchorPos.equals(neighborPos)) {
            Direction negativeHeightDir = getHeightDirection(facing).getOpposite();
            Direction negativeWidthDir = getWidthDirection(facing).getOpposite();
            if (neighborDir == negativeHeightDir || neighborDir == negativeWidthDir) {
                BlockEntity neighborBe = getLoadedBlockEntity(neighborPos);

                if (neighborBe instanceof ScreenBlockEntity neighborScreen) {
                    neighborScreen.updateScreenStructure();
                }
            }
            updateScreenStructure();
        } else {
            if (!level.hasChunkAt(anchorPos)) return;
            BlockEntity anchorBe = getLoadedBlockEntity(anchorPos);
            if (anchorBe instanceof ScreenBlockEntity anchor && anchor.isAnchor()) {
                anchor.updateScreenStructure();
            }
        }
    }
}

