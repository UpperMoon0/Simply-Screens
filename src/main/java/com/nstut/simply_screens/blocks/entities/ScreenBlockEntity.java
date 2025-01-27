package com.nstut.simply_screens.blocks.entities;

import com.nstut.simply_screens.blocks.ScreenBlock;
import com.nstut.simply_screens.network.PacketRegistries;
import com.nstut.simply_screens.network.UpdateScreenS2CPacket;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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
    private int tickSinceLastUpdate = 0;
    private int screenWidth = 1;
    private int screenHeight = 1;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityRegistries.SCREEN.get(), pos, state);
        anchorPos = pos;
    }

    public void setImagePathAndSync(String path) {
        this.imagePath = path;
        updateClients();
        setChanged();
    }

    public void writeTag(CompoundTag tag) {
        tag.putString("imagePath", imagePath);
        if (anchorPos != null) {
            tag.putInt("anchorX", anchorPos.getX());
            tag.putInt("anchorY", anchorPos.getY());
            tag.putInt("anchorZ", anchorPos.getZ());
        }
        tag.putInt("screenWidth", screenWidth);
        tag.putInt("screenHeight", screenHeight);
    }

    public void readTag(CompoundTag tag) {
        if (tag.contains("imagePath")) {
            imagePath = tag.getString("imagePath");
        }
        if (tag.contains("anchorX") && tag.contains("anchorY") && tag.contains("anchorZ")) {
            anchorPos = new BlockPos(tag.getInt("anchorX"), tag.getInt("anchorY"), tag.getInt("anchorZ"));
        }
        if (tag.contains("screenWidth")) {
            screenWidth = tag.getInt("screenWidth");
        }
        if (tag.contains("screenHeight")) {
            screenHeight = tag.getInt("screenHeight");
        }
    }

    @Override
    public void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        writeTag(tag);
        updateClients();
    }

    @Override
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);
        readTag(tag);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        CompoundTag tag = new CompoundTag();
        writeTag(tag);
        return ClientboundBlockEntityDataPacket.create(this, (blockEntity) -> tag);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);
        readTag(tag);
    }

    private void updateClients() {
        if (level != null && !level.isClientSide) {
            UpdateScreenS2CPacket packet = new UpdateScreenS2CPacket(worldPosition, imagePath, anchorPos, screenWidth, screenHeight);
            PacketRegistries.sendToClients(packet);
        }
    }

    public void updateScreen(String imagePath, int screenWidth, int screenHeight, BlockPos anchorPos) {
        if (level == null) {
            return;
        }

        this.imagePath = imagePath;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.anchorPos = anchorPos;
        updateClients();
        setChanged();
        BlockState currentState = getBlockState();
        if (currentState.getBlock() instanceof ScreenBlock) {
            level.setBlock(worldPosition, currentState.setValue(ScreenBlock.STATE, isAnchor()? ScreenBlock.STATE_ANCHOR : ScreenBlock.STATE_CHILD), 3);
        }
    }

    public boolean isAnchor() {
        return (anchorPos != null && anchorPos.equals(worldPosition));
    }

    /**
     * Disables the anchor status of all ScreenBlockEntities within the rectangular region
     * defined by the anchor position (anchorPos) and the far corner (farCorner).
     * The region is determined based on the facing direction of the screen structure.
     *
     * @param farCorner The position of the far corner (top-right corner of the screen structure).
     * @param facing    The direction the screen structure is facing.
     */
    public void updateChildScreens(BlockPos farCorner, Direction facing) {
        if (level == null) {
            return;
        }

        // Define the horizontal and vertical traversal directions based on the screen's facing
        Direction perpendicular = getPerpendicular(facing);

        // Calculate the horizontal and vertical extents
        int horizontalExtent = Math.abs(farCorner.getX() - worldPosition.getX());
        if (facing == Direction.WEST || facing == Direction.EAST) {
            horizontalExtent = Math.abs(farCorner.getZ() - worldPosition.getZ());
        }
        int verticalExtent = farCorner.getY() - worldPosition.getY();

        // Traverse through the rectangular region and disable anchors
        for (int i = 0; i <= horizontalExtent; i++) {
            for (int j = 0; j <= verticalExtent; j++) {
                // Calculate the current block position
                BlockPos currentPos = worldPosition.relative(perpendicular, i).offset(0, j, 0);
                BlockEntity blockEntity = level.getBlockEntity(currentPos);

                // Disable the anchor for ScreenBlockEntities, excluding the anchor block itself
                if (blockEntity instanceof ScreenBlockEntity screenBlockEntity && !currentPos.equals(worldPosition)) {
                    screenBlockEntity.updateScreen(imagePath, screenWidth, screenHeight, worldPosition);
                }
            }
        }
    }

    public void tick() {
        if (level == null) {
            return;
        }

        if (isAnchor()) {
            if (tickSinceLastUpdate++ >= 20) {
                updateScreenStructure();
                tickSinceLastUpdate = 0;
            }
        } else {
            if (anchorPos == null) {
                return;
            }

            BlockEntity blockEntity = level.getBlockEntity(anchorPos);
            if (!(blockEntity instanceof ScreenBlockEntity screenBlockEntity) || !screenBlockEntity.isAnchor()) {
                imagePath = "";
                anchorPos = null;
                screenWidth = 1;
                screenHeight = 1;
                BlockState currentState = getBlockState();
                if (currentState.getBlock() instanceof ScreenBlock) {
                    level.setBlock(worldPosition, currentState.setValue(ScreenBlock.STATE, ScreenBlock.STATE_ERROR), 3);
                }
            }
        }
    }

    public void updateScreenStructure() {
        Direction facing = getBlockState().getValue(ScreenBlock.FACING);
        BlockPos farCorner = getFarCorner(facing);
        if (farCorner == null) {
            return;
        }
        updateChildScreens(farCorner, facing);
        screenWidth = switch (facing) {
            case NORTH, SOUTH -> Math.abs(farCorner.getX() - worldPosition.getX()) + 1;
            case WEST, EAST -> Math.abs(farCorner.getZ() - worldPosition.getZ()) + 1;
            default -> 0;
        };
        screenHeight = farCorner.getY() - worldPosition.getY() + 1;
    }

    /**
     * Determines the farthest corner (top-right) of a rectangular screen structure
     * starting from the given anchor position (bottom-left corner) and facing direction.
     * The method ensures that the farthest corner is part of a valid rectangle of contiguous
     * ScreenBlockEntities, starting from the anchor block.
     * @param facing    The direction the screen structure is facing.
     *                  Valid values are NORTH, SOUTH, EAST, or WEST.
     * @return The position of the farthest corner (top-right) of the rectangular screen structure.
     * @throws IllegalArgumentException If the provided facing direction is invalid.
     */
    private BlockPos getFarCorner(Direction facing) {
        // Ensure the level is valid before proceeding
        if (level == null) {
            return null;
        }

        // Determine the perpendicular directions based on facing
        Direction perpendicular = getPerpendicular(facing);

        // Calculate furthestRight by traversing in the perpendicular direction
        int furthestRight = 0;
        BlockPos currentPos = worldPosition.relative(perpendicular);
        while (level.getBlockEntity(currentPos) instanceof ScreenBlockEntity) {
            furthestRight++;
            currentPos = currentPos.relative(perpendicular);
        }

        // Calculate furthestUp by traversing upwards
        int furthestUp = 0;
        currentPos = worldPosition.above();
        while (level.getBlockEntity(currentPos) instanceof ScreenBlockEntity) {
            furthestUp++;
            currentPos = currentPos.above();
        }

        // Initialize variables for the best rectangle dimensions
        int bestRight = 0;
        int bestUp = 0;

        // Check all rectangles within the extents
        for (int right = 0; right <= furthestRight; right++) {
            for (int up = 0; up <= furthestUp; up++) {
                boolean validRectangle = true;

                // Validate the rectangle by checking all positions within it
                for (int x = 0; x <= right && validRectangle; x++) {
                    for (int y = 0; y <= up; y++) {
                        BlockPos checkPos = worldPosition.relative(perpendicular, x).above(y);
                        if (!checkPos.equals(worldPosition)
                                && !(level.getBlockEntity(checkPos) instanceof ScreenBlockEntity)) {
                            validRectangle = false;
                            break;
                        }
                    }
                }

                // Update the best rectangle dimensions if a larger valid rectangle is found
                if (validRectangle && (right + 1) * (up + 1) > (bestRight + 1) * (bestUp + 1)) {
                    bestRight = right;
                    bestUp = up;
                }
            }
        }

        // Calculate the far corner based on the best rectangle's dimensions
        return worldPosition.relative(perpendicular, bestRight).above(bestUp);
    }

    private Direction getPerpendicular(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
            default -> throw new IllegalArgumentException("Invalid facing direction");
        };
    }

    public void onRemoved() {
        if (level == null || level.isClientSide) {
            return;
        }

        if (isAnchor()) {
            LOGGER.info("Anchor block broken at " + worldPosition);
            if (screenWidth == 1 && screenHeight == 1) {
                return;
            }

            Direction facing = getBlockState().getValue(ScreenBlock.FACING);
            Direction perpendicular = getPerpendicular(facing);

            int topArea = screenWidth * (screenHeight - 1);
            int rightArea = (screenWidth - 1) * screenHeight;
            if (topArea > rightArea) {
                ScreenBlockEntity screenBlockEntity = (ScreenBlockEntity) level.getBlockEntity(worldPosition.above());
                if (screenBlockEntity != null) {
                    screenBlockEntity.updateScreen(imagePath, screenWidth, screenHeight, screenBlockEntity.worldPosition);
                }
            } else {
                ScreenBlockEntity screenBlockEntity = (ScreenBlockEntity) level.getBlockEntity(worldPosition.relative(perpendicular));
                if (screenBlockEntity != null) {
                    screenBlockEntity.updateScreen(imagePath, screenWidth, screenHeight, screenBlockEntity.worldPosition);
                }
            }
        }
    }
}
