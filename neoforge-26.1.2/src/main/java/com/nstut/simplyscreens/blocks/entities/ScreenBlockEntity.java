package com.nstut.simplyscreens.blocks.entities;

import com.nstut.simplyscreens.Config;
import com.nstut.simplyscreens.ScreenRegistry;
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
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import java.util.stream.IntStream;

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

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityRegistries.SCREEN.get(), pos, state);
        this.anchorPos = pos;
        
        // Log the block type for debugging
        if (state != null && state.getBlock() != null) {
            System.out.println("ScreenBlockEntity created with block type: " + state.getBlock().getClass().getName());
            SimplyScreens.LOGGER.info("ScreenBlockEntity created with block type: " + state.getBlock().getClass().getName());
        }
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
        if (isAnchor() && imageId != null) {
            tag.putString("imageId", imageId.toString());
        }
        if (isAnchor() && screenId != null && !screenId.isEmpty()) {
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
        screenId = tag.getStringOr("screenId", "");
        maintainAspectRatio = tag.getBooleanOr("maintainAspectRatio", true);
        screenWidth = tag.getIntOr("screenWidth", 1);
        screenHeight = tag.getIntOr("screenHeight", 1);

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
            UpdateScreenS2CPacket packet = new UpdateScreenS2CPacket(worldPosition, resolvedImageId, maintainAspectRatio, screenId);
            if (level.getServer() != null) {
                for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
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
            this.screenId = screenId != null ? screenId : "";
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
        if (level == null || level.isClientSide() || !isAnchor()) {
            return;
        }

        String oldScreenId = this.screenId;
        this.screenId = screenId != null ? screenId : "";
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
        this.screenId = screenId != null ? screenId : "";
    }

    public void updateScreen(UUID imageId, int width, int height, BlockPos anchor, boolean maintainAspect) {
        if (level == null || level.isClientSide()) return;

        this.imageId = imageId;
        this.screenWidth = width;
        this.screenHeight = height;
        this.anchorPos = anchor;
        this.maintainAspectRatio = maintainAspect;

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
            if (!screenLinkRegistered && screenId != null && !screenId.isEmpty()) {
                ScreenRegistry.registerScreen(level, worldPosition, screenId);
                screenLinkRegistered = true;
            }
            if (tickSinceLastUpdate++ >= Config.SCREEN_TICK_RATE) {
                updateScreenStructure();
                tickSinceLastUpdate = 0;
            }
        } else {
            verifyAnchorValidity();
        }
    }

    public void updateScreenStructure() {
        Direction facing = getBlockState().hasProperty(ScreenBlock.FACING) ?
            getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        BlockPos farCorner = calculateStructureBounds(facing);

        if (farCorner != null) {
            calculateScreenDimensions(facing, farCorner);

            // Add this line to force immediate client update
            this.updateScreen(this.imageId, screenWidth, screenHeight, worldPosition, maintainAspectRatio);

            updateChildScreens(farCorner, facing);
        }
    }

    public void markForRenderUpdate() {
        if (level != null && !level.isClientSide()) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
        }
    }

    private void updateChildScreens(BlockPos farCorner, Direction facing) {
        if (level == null || level.isClientSide()) return;

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
        if (level == null || level.isClientSide()) return;

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
    }

    private BlockPos calculateStructureBounds(Direction facing) {
        if (level == null || level.isClientSide()) return null;

        if (isHorizontal(facing)) {
            // Existing horizontal facing logic unchanged
            Direction widthDirection = getWidthDirection(facing);
            int maxHorizontal = findMaxExtension(widthDirection);
            int maxVertical = findMaxExtension(Direction.UP);

            int bestWidth = 0;
            int bestHeight = 0;

            for (int w = 0; w <= maxHorizontal; w++) {
                for (int h = 0; h <= maxVertical; h++) {
                    if (isValidHorizontalStructure(w, h, widthDirection)) {
                        if ((w + 1) * (h + 1) > (bestWidth + 1) * (bestHeight + 1)) {
                            bestWidth = w;
                            bestHeight = h;
                        }
                    }
                }
            }

            return worldPosition.relative(widthDirection, bestWidth).above(bestHeight);
        } else {
            // Vertical facing (up/down)
            if (facing == Direction.UP) {
                // Up-facing: expand WEST (width) and SOUTH (height)
                int maxWest = findMaxExtension(Direction.WEST);
                int maxSouth = findMaxExtension(Direction.SOUTH);

                int bestWidth = 0;
                int bestHeight = 0;

                for (int w = 0; w <= maxWest; w++) {
                    for (int h = 0; h <= maxSouth; h++) {
                        if (isValidVerticalStructure(w, h, Direction.SOUTH)) {
                            if ((w + 1) * (h + 1) > (bestWidth + 1) * (bestHeight + 1)) {
                                bestWidth = w;
                                bestHeight = h;
                            }
                        }
                    }
                }
                return worldPosition.relative(Direction.WEST, bestWidth).relative(Direction.SOUTH, bestHeight);
            } else {
                // Down-facing: expand WEST (width) and NORTH (height)
                int maxWest = findMaxExtension(Direction.WEST);
                int maxNorth = findMaxExtension(Direction.NORTH);

                int bestWidth = 0;
                int bestHeight = 0;

                for (int w = 0; w <= maxWest; w++) {
                    for (int h = 0; h <= maxNorth; h++) {
                        if (isValidVerticalStructure(w, h, Direction.NORTH)) {
                            if ((w + 1) * (h + 1) > (bestWidth + 1) * (bestHeight + 1)) {
                                bestWidth = w;
                                bestHeight = h;
                            }
                        }
                    }
                }
                return worldPosition.relative(Direction.WEST, bestWidth).relative(Direction.NORTH, bestHeight);
            }
        }
    }

    private boolean isValidHorizontalStructure(int width, int height, Direction direction) {
        if (level == null || level.isClientSide()) return false;

        return IntStream.rangeClosed(0, width)
                .allMatch(i -> IntStream.rangeClosed(0, height)
                        .allMatch(j -> {
                            BlockPos checkPos = worldPosition.relative(direction, i).above(j);
                            return checkPos.equals(worldPosition) || (level.getBlockEntity(checkPos) instanceof ScreenBlockEntity);
                        }));
    }

    private boolean isValidVerticalStructure(int width, int height, Direction heightDir) {
        if (level == null) return false;

        return IntStream.rangeClosed(0, width)
                .allMatch(w -> IntStream.rangeClosed(0, height)
                        .allMatch(h -> {
                            BlockPos checkPos = worldPosition.relative(Direction.WEST, w).relative(heightDir, h);
                            return checkPos.equals(worldPosition) || (level.getBlockEntity(checkPos) instanceof ScreenBlockEntity);
                        }));
    }


    private int findMaxExtension(Direction direction) {
        if (level == null) return 0;

        int extension = 0;
        BlockPos current = worldPosition.relative(direction);

        while (level.getBlockEntity(current) instanceof ScreenBlockEntity) {
            extension++;
            current = current.relative(direction);
        }

        return extension;
    }

    private void verifyAnchorValidity() {
        if (anchorPos == null || level == null) return;

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
        if (level == null || level.isClientSide()) return;

        // Immediate structure update before promotion
        updateScreenStructure();

        if (screenWidth == 1 && screenHeight == 1) return;

        Direction facing = getBlockState().hasProperty(ScreenBlock.FACING) ?
            getBlockState().getValue(ScreenBlock.FACING) : Direction.NORTH;
        BlockPos newAnchorPos = findNewAnchorPosition(facing);

        BlockEntity newAnchorBe = level.getBlockEntity(newAnchorPos);
        if (newAnchorBe instanceof ScreenBlockEntity newAnchor) {
            // Force immediate update of new anchor
            newAnchor.updateScreenStructure();
            newAnchor.updateScreen(this.imageId, this.screenWidth, this.screenHeight, newAnchorPos, this.maintainAspectRatio);

            // Update children immediately
            updateChildrenToNewAnchor(newAnchorPos, facing);

            // Force render updates
            newAnchor.markForRenderUpdate();
        }

        // Immediately update network sync
        if (level.getServer() != null) {
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                PacketRegistries.sendToPlayer(player, new UpdateScreenS2CPacket(
                        newAnchorPos,
                        imageId,
                        maintainAspectRatio,
                        screenId
                ));
            }
        }
    }

    private BlockPos findNewAnchorPosition(Direction facing) {
        boolean promoteAbove = (screenWidth * (screenHeight - 1)) > ((screenWidth - 1) * screenHeight);

        if (promoteAbove) {
            return worldPosition.relative(getHeightDirection(facing));
        } else {
            return worldPosition.relative(getWidthDirection(facing));
        }
    }

    private void updateChildrenToNewAnchor(BlockPos newAnchorPos, Direction facing) {
        if (level == null || level.isClientSide()) return;

        for (int x = 0; x < screenWidth; x++) {
            for (int y = 0; y < screenHeight; y++) {
                BlockPos childPos = calculateChildPosition(facing, x, y);
                if (childPos.equals(worldPosition)) continue;

                BlockEntity be = level.getBlockEntity(childPos);
                if (be instanceof ScreenBlockEntity child) {
                    child.setAnchorPos(newAnchorPos);
                    child.updateClients();

                    // Force immediate block update
                    child.markForRenderUpdate();
                    level.sendBlockUpdated(childPos, child.getBlockState(), child.getBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private BlockPos calculateChildPosition(Direction facing, int x, int y) {
        Direction widthDirection = getWidthDirection(facing);
        Direction heightDirection = getHeightDirection(facing);
        return worldPosition.relative(widthDirection, x).relative(heightDirection, y);
    }

    public void onNeighborRemoved() {
        if (level == null || level.isClientSide() || anchorPos == null) return;

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
        if (level == null || level.isClientSide() || anchorPos == null) return;

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

