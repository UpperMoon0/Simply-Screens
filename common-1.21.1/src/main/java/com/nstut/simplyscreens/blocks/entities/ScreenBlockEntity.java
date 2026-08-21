package com.nstut.simplyscreens.blocks.entities;

import com.nstut.simplyscreens.Config;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
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
    private boolean needsLoadReconciliation = true;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityRegistries.SCREEN.get(), pos, state);
        this.anchorPos = pos;
        
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        writePersistentData(tag);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        readPersistentData(tag);
    }

    private void writePersistentData(CompoundTag tag) {
        if (imageId != null) {
            tag.putUUID("imageId", imageId);
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

    private void readPersistentData(CompoundTag tag) {
        if (tag.hasUUID("imageId")) {
            imageId = tag.getUUID("imageId");
        } else {
            imageId = null;
        }
        screenId = com.nstut.simplyscreens.ScreenRegistryHelper.normalizeScreenId(tag.contains("screenId") ? tag.getString("screenId") : "");
        maintainAspectRatio = !tag.contains("maintainAspectRatio") || tag.getBoolean("maintainAspectRatio");
        screenWidth = tag.contains("screenWidth") ? Math.max(1, tag.getInt("screenWidth")) : 1;
        screenHeight = tag.contains("screenHeight") ? Math.max(1, tag.getInt("screenHeight")) : 1;

        if (tag.contains("anchorX") && tag.contains("anchorY") && tag.contains("anchorZ")) {
            anchorPos = new BlockPos(
                    tag.getInt("anchorX"),
                    tag.getInt("anchorY"),
                    tag.getInt("anchorZ")
            );
        }
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        writePersistentData(tag);
        return tag;
    }

    private void updateClients() {
        if (level != null && !level.isClientSide) {
            UUID resolvedImageId = getResolvedImageId();
            UpdateScreenS2CPacket packet = new UpdateScreenS2CPacket(worldPosition, anchorPos, resolvedImageId, maintainAspectRatio, screenId, screenWidth, screenHeight);
            if (level instanceof ServerLevel serverLevel) {
                for (ServerPlayer player : serverLevel.getChunkSource().chunkMap.getPlayers(new ChunkPos(worldPosition), false)) {
                    PacketRegistries.sendToPlayer(player, packet);
                }
            }
        }
    }

    public void setImageId(UUID imageId) {
        if (level != null && level.isClientSide) {
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
        if (level != null && level.isClientSide) {
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
        if (screenId != null && !screenId.isEmpty()) {
            UUID registryImageId = ScreenRegistry.getImageId(screenId);
            if (registryImageId != null) {
                return registryImageId;
            }
        }
        return imageId;
    }

    public void setMaintainAspectRatio(boolean maintainAspectRatio) {
        if (level != null && level.isClientSide) {
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
        if (level == null || level.isClientSide || !isAnchor()) {
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
                BlockEntity be = level.getBlockEntity(currentPos);
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
            BlockEntity be = level.getBlockEntity(anchorPos);
            if (be instanceof ScreenBlockEntity) {
                return (ScreenBlockEntity) be;
            }
        }
        return null;
    }

    public void forceImageId(UUID imageId) {
        if (level == null || level.isClientSide || !isAnchor()) {
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
                BlockEntity be = level.getBlockEntity(currentPos);
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

    private void broadcastImageIdToLinkedScreens(UUID imageId) {
        if (level == null || screenId == null || screenId.isEmpty()) return;

        for (BlockPos pos : ScreenRegistry.getPositionsForScreenId(level, screenId)) {
            if (pos.equals(worldPosition)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ScreenBlockEntity linkedScreen && linkedScreen.isAnchor()) {
                linkedScreen.applyLinkedImageId(imageId);
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
                BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(widthDirection, w).relative(heightDirection, h));
                if (blockEntity instanceof ScreenBlockEntity screen) {
                    screen.updateScreen(linkedImageId, screenWidth, screenHeight, worldPosition, maintainAspectRatio);
                }
            }
        }
        updateClients();
    }

    public void forceScreenId(String screenId) {
        if (level == null || level.isClientSide || !isAnchor()) {
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
                BlockEntity be = level.getBlockEntity(currentPos);
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
        if (level == null || level.isClientSide) return;

        this.imageId = imageId;
        this.screenWidth = width;
        this.screenHeight = height;
        this.anchorPos = anchor;
        this.maintainAspectRatio = maintainAspect;
        setChanged();

        updateClients();

        if (getBlockState().getBlock() instanceof ScreenBlock) {
            BlockState newState = getBlockState().setValue(
                    ScreenBlock.STATE,
                    isAnchor() ? ScreenBlock.STATE_ANCHOR : ScreenBlock.STATE_CHILD
            );
            level.setBlock(worldPosition, newState, Block.UPDATE_ALL);
        }
    }

    public void tick() {
        if (level == null || level.isClientSide) return;

        if (isAnchor()) {
            if (needsStructureRefresh && ++tickSinceLastUpdate >= 100) {
                tickSinceLastUpdate = 0;
                updateScreenStructure();
            }
            if (!screenLinkRegistered && screenId != null && !screenId.isEmpty()) {
                ScreenRegistry.registerScreen(level, worldPosition, screenId);
                screenLinkRegistered = true;
                UUID registryImage = ScreenRegistry.getImageId(screenId);
                if (registryImage != null && !registryImage.equals(imageId)) {
                    applyLinkedImageId(registryImage);
                }
            }
        } else if (needsLoadReconciliation && anchorPos != null && ++tickSinceLastUpdate >= 100) {
            tickSinceLastUpdate = 0;
            if (level.getBlockEntity(anchorPos) instanceof ScreenBlockEntity anchor && anchor.isAnchor()) {
                updateScreen(anchor.imageId, anchor.screenWidth, anchor.screenHeight, anchor.worldPosition, anchor.maintainAspectRatio);
                setScreenIdInternal(anchor.screenId);
                anchor.needsStructureRefresh = true;
                anchor.updateScreenStructure();
                needsLoadReconciliation = false;
            }
        }
    }

    public void updateScreenStructure() {
        Direction facing = getBlockState().hasProperty(ScreenBlock.FACING) ?
            getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        if (!isCurrentStructureLoaded(facing)) {
            needsStructureRefresh = true;
            return;
        }
        BlockPos farCorner = calculateStructureBounds(facing);

        if (farCorner != null) {
            needsStructureRefresh = false;
            calculateScreenDimensions(facing, farCorner);

            // Add this line to force immediate client update
            this.updateScreen(this.imageId, screenWidth, screenHeight, worldPosition, maintainAspectRatio);

            updateChildScreens(farCorner, facing);
        } else {
            needsStructureRefresh = true;
        }
    }

    public void markForRenderUpdate() {
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
        }
    }

    private void updateChildScreens(BlockPos farCorner, Direction facing) {
        if (level == null || level.isClientSide) return;

        if (isHorizontal(facing)) {
            // Existing horizontal logic unchanged
            Direction widthDirection = getWidthDirection(facing);
            int horizontalExtent = facing.getAxis() == Direction.Axis.Z ?
                    Math.abs(farCorner.getX() - worldPosition.getX()) :
                    Math.abs(farCorner.getZ() - worldPosition.getZ());

            int verticalExtent = farCorner.getY() - worldPosition.getY();

            for (int i = 0; i <= horizontalExtent; i++) {
                for (int j = 0; j <= verticalExtent; j++) {
                    BlockPos currentPos = worldPosition.relative(widthDirection, i).above(j);
                    BlockEntity be = level.getBlockEntity(currentPos);

                    if (be instanceof ScreenBlockEntity childEntity && !currentPos.equals(worldPosition)) {
                        if (childEntity.isAnchor() && childEntity.imageId != null) {
                            this.imageId = childEntity.imageId;
                        }
                        childEntity.updateScreen(this.imageId, screenWidth, screenHeight, worldPosition, maintainAspectRatio);
                    }
                }
            }
        } else {
            // Vertical facing (up/down)
            if (facing == Direction.UP) {
                int width = worldPosition.getX() - farCorner.getX();
                int height = farCorner.getZ() - worldPosition.getZ();

                for (int w = 0; w <= width; w++) {
                    for (int h = 0; h <= height; h++) {
                        BlockPos currentPos = worldPosition.relative(Direction.WEST, w)
                                .relative(Direction.SOUTH, h);
                        updateChildAtPosition(currentPos);
                    }
                }
            } else {
                int width = worldPosition.getX() - farCorner.getX();
                int height = worldPosition.getZ() - farCorner.getZ();

                for (int w = 0; w <= width; w++) {
                    for (int h = 0; h <= height; h++) {
                        BlockPos currentPos = worldPosition.relative(Direction.WEST, w)
                                .relative(Direction.NORTH, h);
                        updateChildAtPosition(currentPos);
                    }
                }
            }
        }
    }

    private static boolean isHorizontal(Direction facing) {
        return facing.getAxis().isHorizontal();
    }

    private void updateChildAtPosition(BlockPos currentPos) {
        if (level == null || level.isClientSide) return;

        BlockEntity be = level.getBlockEntity(currentPos);
        if (be instanceof ScreenBlockEntity childEntity && !currentPos.equals(worldPosition)) {
            if (childEntity.isAnchor() && childEntity.imageId != null) {
                this.imageId = childEntity.imageId;
            }
            childEntity.updateScreen(this.imageId, screenWidth, screenHeight, worldPosition, maintainAspectRatio);
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

        markForRenderUpdate();
    }

    private BlockPos calculateStructureBounds(Direction facing) {
        if (level == null || level.isClientSide) return null;
        try {
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
                if (!level.hasChunkAt(worldPosition.relative(widthDirection, width).relative(heightDirection, height))) return false;
            }
        }
        return true;
    }


    private int findMaxExtension(Direction direction, Direction facing) {
        if (level == null) return 0;

        int extension = 0;
        BlockPos current = worldPosition.relative(direction);

        while (isMatchingScreen(current, facing)) {
            extension++;
            current = current.relative(direction);
        }

        return extension;
    }

    private boolean isMatchingScreen(BlockPos pos, Direction facing) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof ScreenBlockEntity screen) || !entity.getBlockState().hasProperty(ScreenBlock.FACING)
                || entity.getBlockState().getValue(ScreenBlock.FACING) != facing) return false;
        BlockPos foreignAnchor = screen.getAnchorPos();
        if (foreignAnchor != null && !foreignAnchor.equals(screen.getBlockPos()) && !level.hasChunkAt(foreignAnchor)) {
            throw new UnresolvedStructureException();
        }
        return true;
    }

    private static final class UnresolvedStructureException extends RuntimeException { }

    private void verifyAnchorValidity() {
        if (anchorPos == null || level == null) return;
        if (!level.hasChunkAt(anchorPos)) return;

        BlockEntity be = level.getBlockEntity(anchorPos);
        if (!(be instanceof ScreenBlockEntity anchorEntity) || !anchorEntity.isAnchor()) {
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

    /** NeoForge/Forge use this full structure box for block-entity frustum and section culling. */
    public AABB getRenderBoundingBox() {
        BlockPos anchor = anchorPos != null ? anchorPos : worldPosition;
        Direction facing = getBlockState().hasProperty(ScreenBlock.FACING)
                ? getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
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
            case UP, DOWN -> Direction.WEST; // Changed from NORTH to WEST
        };
    }

    private static Direction getHeightDirection(Direction facing) {
        return isHorizontal(facing) ? Direction.UP : facing == Direction.UP ? Direction.SOUTH : Direction.NORTH;
    }

    public void findNewAnchor() {
        if (level == null || level.isClientSide) return;

        ScreenAnchorPromotion.Result promotion = ScreenAnchorPromotion.choose(screenWidth, screenHeight);
        if (promotion.axis() == ScreenAnchorPromotion.Axis.NONE) return;

        Direction facing = getBlockState().hasProperty(ScreenBlock.FACING) ?
            getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        BlockPos newAnchorPos = worldPosition.relative(promotion.axis() == ScreenAnchorPromotion.Axis.HEIGHT
                ? getHeightDirection(facing) : getWidthDirection(facing));

        BlockEntity newAnchorBe = level.getBlockEntity(newAnchorPos);
        if (newAnchorBe instanceof ScreenBlockEntity newAnchor) {
            newAnchor.updateScreen(this.imageId, promotion.width(), promotion.height(), newAnchorPos, this.maintainAspectRatio);
            newAnchor.setScreenIdInternal(this.screenId);
            updateChildrenToNewAnchor(newAnchorPos, facing, promotion.width(), promotion.height());
            newAnchor.updateScreenStructure();
            newAnchor.markForRenderUpdate();

            if (level instanceof ServerLevel serverLevel) {
                for (ServerPlayer player : serverLevel.getChunkSource().chunkMap.getPlayers(new ChunkPos(newAnchorPos), false)) {
                    PacketRegistries.sendToPlayer(player, new UpdateScreenS2CPacket(
                            newAnchorPos, newAnchorPos, imageId, maintainAspectRatio, screenId,
                            promotion.width(), promotion.height()));
                }
            }
        }
    }

    private void updateChildrenToNewAnchor(BlockPos newAnchorPos, Direction facing, int width, int height) {
        if (level == null || level.isClientSide) return;

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                BlockPos childPos = calculateChildPosition(newAnchorPos, facing, x, y);
                if (childPos.equals(newAnchorPos)) continue;

                BlockEntity be = level.getBlockEntity(childPos);
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
        if (level == null || level.isClientSide || anchorPos == null) return;

        if (anchorPos.equals(worldPosition)) {
            updateScreenStructure();
        } else {
            BlockEntity anchorBe = level.getBlockEntity(anchorPos);
            if (anchorBe instanceof ScreenBlockEntity anchor) {
                anchor.updateScreenStructure();
            }
        }
    }

    public void onNeighborPlaced(BlockPos neighborPos, Direction neighborDir) {
        if (level == null || level.isClientSide || anchorPos == null) return;

        Direction facing = getBlockState().hasProperty(ScreenBlock.FACING) ?
            getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;

        if (anchorPos.equals(neighborPos)) {
            Direction negativeHeightDir = getHeightDirection(facing).getOpposite();
            Direction negativeWidthDir = getWidthDirection(facing).getOpposite();
            if (neighborDir == negativeHeightDir || neighborDir == negativeWidthDir) {
                BlockEntity neighborBe = level.getBlockEntity(neighborPos);

                if (neighborBe instanceof ScreenBlockEntity neighborScreen) {
                    neighborScreen.updateScreenStructure();
                }
            }
            updateScreenStructure();
        } else {
            BlockEntity anchorBe = level.getBlockEntity(anchorPos);
            if (anchorBe instanceof ScreenBlockEntity anchor && anchor.isAnchor()) {
                anchor.updateScreenStructure();
            }
        }
    }
}
