package com.nstut.simplyscreens.blocks.entities;

import com.nstut.simplyscreens.blocks.ScreenBlock;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.UpdateScreenS2CPacket;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

@Setter
@Getter
public class ScreenBlockEntity extends BlockEntity {
    private static final Logger LOGGER = Logger.getLogger(ScreenBlockEntity.class.getName());

    private String imagePath = "";
    private BlockPos anchorPos;
    private int screenWidth = 1;
    private int screenHeight = 1;
    private boolean maintainAspectRatio = true;
    private int tickSinceLastUpdate = 0;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityRegistries.SCREEN.get(), pos, state);
        this.anchorPos = pos;
    }

    @Override
    public void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        writePersistentData(tag);
        updateClients();
    }

    @Override
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);
        readPersistentData(tag);
    }

    private void writePersistentData(CompoundTag tag) {
        tag.putString("imagePath", imagePath);
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
        imagePath = tag.getString("imagePath");
        maintainAspectRatio = tag.getBoolean("maintainAspectRatio");
        screenWidth = tag.getInt("screenWidth");
        screenHeight = tag.getInt("screenHeight");

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
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);
        readPersistentData(tag);
    }

    private void updateClients() {
        if (level != null && !level.isClientSide) {
            UpdateScreenS2CPacket packet = new UpdateScreenS2CPacket(
                    worldPosition,
                    imagePath,
                    anchorPos,
                    screenWidth,
                    screenHeight,
                    maintainAspectRatio
            );
            PacketRegistries.sendToClients(packet);
        }
    }

    public void updateFromScreenInputs(String imagePath, boolean maintainAspectRatio) {
        this.imagePath = imagePath;
        this.maintainAspectRatio = maintainAspectRatio;
        updateClients();
        setChanged();
    }

    public void updateScreen(String imagePath, int width, int height, BlockPos anchor, boolean maintainAspect) {
        if (level == null) return;

        this.imagePath = imagePath;
        this.screenWidth = width;
        this.screenHeight = height;
        this.anchorPos = anchor;
        this.maintainAspectRatio = maintainAspect;

        updateClients();
        setChanged();

        if (getBlockState().getBlock() instanceof ScreenBlock) {
            BlockState newState = getBlockState().setValue(
                    ScreenBlock.STATE,
                    isAnchor() ? ScreenBlock.STATE_ANCHOR : ScreenBlock.STATE_CHILD
            );
            level.setBlock(worldPosition, newState, Block.UPDATE_ALL);
        }
    }

    public void tick() {
        if (level == null) return;

        if (isAnchor()) {
            if (tickSinceLastUpdate++ >= 20) {
                updateScreenStructure();
                tickSinceLastUpdate = 0;
            }
        } else {
            verifyAnchorValidity();
        }
    }

    private void updateScreenStructure() {
        Direction facing = getBlockState().getValue(ScreenBlock.FACING);
        BlockPos farCorner = calculateStructureBounds(facing);

        if (farCorner != null) {
            updateChildScreens(farCorner, facing);
            calculateScreenDimensions(facing, farCorner);
        }
    }

    private void updateChildScreens(BlockPos farCorner, Direction facing) {
        if (level == null) return;

        if (facing.getAxis().isHorizontal()) {
            // Existing horizontal logic unchanged
            Direction perpendicular = getPerpendicularDirection(facing);
            int horizontalExtent = facing.getAxis() == Direction.Axis.Z ?
                    Math.abs(farCorner.getX() - worldPosition.getX()) :
                    Math.abs(farCorner.getZ() - worldPosition.getZ());

            int verticalExtent = farCorner.getY() - worldPosition.getY();

            for (int i = 0; i <= horizontalExtent; i++) {
                for (int j = 0; j <= verticalExtent; j++) {
                    BlockPos currentPos = worldPosition.relative(perpendicular, i).above(j);
                    BlockEntity be = level.getBlockEntity(currentPos);

                    if (be instanceof ScreenBlockEntity childEntity && !currentPos.equals(worldPosition)) {
                        if (childEntity.isAnchor() && !childEntity.imagePath.isBlank()) {
                            this.imagePath = childEntity.imagePath;
                        }
                        childEntity.updateScreen(imagePath, screenWidth, screenHeight, worldPosition, maintainAspectRatio);
                    }
                }
            }
        } else {
            // Vertical facing (up/down) - FIXED CHILD POSITIONS
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

    private void updateChildAtPosition(BlockPos currentPos) {
        if (level == null) return;

        BlockEntity be = level.getBlockEntity(currentPos);
        if (be instanceof ScreenBlockEntity childEntity && !currentPos.equals(worldPosition)) {
            if (childEntity.isAnchor() && !childEntity.imagePath.isBlank()) {
                this.imagePath = childEntity.imagePath;
            }
            childEntity.updateScreen(imagePath, screenWidth, screenHeight, worldPosition, maintainAspectRatio);
        }
    }

    private void calculateScreenDimensions(Direction facing, BlockPos farCorner) {
        if (facing.getAxis().isHorizontal()) {
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
        if (level == null) return null;

        if (facing.getAxis().isHorizontal()) {
            // Existing horizontal facing logic unchanged
            Direction perpendicular = getPerpendicularDirection(facing);
            int maxHorizontal = findMaxExtension(perpendicular);
            int maxVertical = findMaxExtension(Direction.UP);

            int bestWidth = 0;
            int bestHeight = 0;

            for (int w = 0; w <= maxHorizontal; w++) {
                for (int h = 0; h <= maxVertical; h++) {
                    if (isValidStructureHorizontal(w, h, perpendicular)) {
                        if ((w + 1) * (h + 1) > (bestWidth + 1) * (bestHeight + 1)) {
                            bestWidth = w;
                            bestHeight = h;
                        }
                    }
                }
            }

            return worldPosition.relative(perpendicular, bestWidth).above(bestHeight);
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
                        if (isValidStructureVertical(w, h, Direction.SOUTH)) {
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
                        if (isValidStructureVertical(w, h, Direction.NORTH)) {
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

    private boolean isValidStructureHorizontal(int width, int height, Direction direction) {
        if (level == null) return false;

        for (int i = 0; i <= width; i++) {
            for (int j = 0; j <= height; j++) {
                BlockPos checkPos = worldPosition.relative(direction, i).above(j);

                if (!checkPos.equals(worldPosition) &&
                        !(level.getBlockEntity(checkPos) instanceof ScreenBlockEntity)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isValidStructureVertical(int width, int height, Direction heightDir) {
        if (level == null) return false;

        for (int w = 0; w <= width; w++) {
            for (int h = 0; h <= height; h++) {
                BlockPos checkPos = worldPosition.relative(Direction.WEST, w).relative(heightDir, h);
                if (!checkPos.equals(worldPosition) &&
                        !(level.getBlockEntity(checkPos) instanceof ScreenBlockEntity)) {
                    return false;
                }
            }
        }
        return true;
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
            resetToDefaultState();
        }
    }

    private void resetToDefaultState() {
        if (level == null) return;

        imagePath = "";
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

    private static Direction getPerpendicularDirection(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
            case UP, DOWN -> Direction.NORTH;
        };
    }

    public void onRemoved() {
        if (level == null || level.isClientSide || !isAnchor()) return;
        if (screenWidth == 1 && screenHeight == 1) return;

        Direction facing = getBlockState().getValue(ScreenBlock.FACING);
        Direction perpendicular = getPerpendicularDirection(facing);

        boolean promoteVertical = (screenWidth * (screenHeight - 1)) > ((screenWidth - 1) * screenHeight);
        BlockPos newAnchorPos = promoteVertical ?
                worldPosition.above() :
                worldPosition.relative(perpendicular);

        BlockEntity newAnchor = level.getBlockEntity(newAnchorPos);
        if (newAnchor instanceof ScreenBlockEntity screenEntity) {
            screenEntity.updateScreen(imagePath, screenWidth, screenHeight, newAnchorPos, maintainAspectRatio);
        }
    }
}