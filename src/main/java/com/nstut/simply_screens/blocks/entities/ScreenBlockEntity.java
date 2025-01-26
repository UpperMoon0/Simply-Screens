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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

@Setter
@Getter
public class ScreenBlockEntity extends BlockEntity {

    private static final Logger LOGGER = Logger.getLogger(ScreenBlockEntity.class.getName());

    private String imagePath = "";
    private boolean isAnchor = true;
    private int tickSinceLastUpdate = 0;
    private int screenWidth;
    private int screenHeight;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityRegistries.SCREEN.get(), pos, state);
    }

    public void setImagePathAndSync(String path) {
        this.imagePath = path;
        updateClients();
        setChanged();
    }

    public void writeTag(CompoundTag tag) {
        tag.putString("imagePath", imagePath);
        tag.putBoolean("isAnchor", isAnchor);
        tag.putInt("screenWidth", screenWidth);
        tag.putInt("screenHeight", screenHeight);
    }

    public void readTag(CompoundTag tag) {
        if (tag.contains("imagePath")) {
            imagePath = tag.getString("imagePath");
        }
        if (tag.contains("isAnchor")) {
            isAnchor = tag.getBoolean("isAnchor");
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
            UpdateScreenS2CPacket packet = new UpdateScreenS2CPacket(worldPosition, imagePath, isAnchor, screenWidth, screenHeight);
            PacketRegistries.sendToClients(packet);
        }
    }

    public void disableAnchor() {
        isAnchor = false;
        updateClients();
        setChanged();
    }

    /**
     * Disables the anchor status of all ScreenBlockEntities within the rectangular region
     * defined by the anchor position (anchorPos) and the far corner (farCorner).
     * The region is determined based on the facing direction of the screen structure.
     *
     * @param level     The current level where the blocks reside.
     * @param anchorPos The position of the anchor block (bottom-left corner of the screen structure).
     * @param farCorner The position of the far corner (top-right corner of the screen structure).
     * @param facing    The direction the screen structure is facing.
     */
    public void disableAnchors(Level level, BlockPos anchorPos, BlockPos farCorner, Direction facing) {
        // Define the horizontal and vertical traversal directions based on the screen's facing
        Direction horizontalDirection;
        switch (facing) {
            case NORTH -> horizontalDirection = Direction.WEST;
            case SOUTH -> horizontalDirection = Direction.EAST;
            case WEST -> horizontalDirection = Direction.SOUTH;
            case EAST -> horizontalDirection = Direction.NORTH;
            default -> throw new IllegalArgumentException("Invalid facing direction: " + facing);
        }

        // Calculate the horizontal and vertical extents
        int horizontalExtent = Math.abs(farCorner.getX() - anchorPos.getX());
        if (facing == Direction.WEST || facing == Direction.EAST) {
            horizontalExtent = Math.abs(farCorner.getZ() - anchorPos.getZ());
        }
        int verticalExtent = farCorner.getY() - anchorPos.getY();

        // Traverse through the rectangular region and disable anchors
        for (int i = 0; i <= horizontalExtent; i++) {
            for (int j = 0; j <= verticalExtent; j++) {
                // Calculate the current block position
                BlockPos currentPos = anchorPos.relative(horizontalDirection, i).offset(0, j, 0);
                BlockEntity blockEntity = level.getBlockEntity(currentPos);

                // Disable the anchor for ScreenBlockEntities, excluding the anchor block itself
                if (blockEntity instanceof ScreenBlockEntity screenBlockEntity && !currentPos.equals(anchorPos)) {
                    screenBlockEntity.disableAnchor();
                }
            }
        }
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        if (isAnchor && tickSinceLastUpdate++ >= 20) {
            Direction facing = state.getValue(ScreenBlock.FACING);
            BlockPos farCorner = getFarCorner(level, pos, facing);
            disableAnchors(level, pos, farCorner, facing);
            screenWidth = switch (facing) {
                case NORTH, SOUTH -> Math.abs(farCorner.getX() - pos.getX()) + 1;
                case WEST, EAST -> Math.abs(farCorner.getZ() - pos.getZ()) + 1;
                default -> 0;
            };
            screenHeight = farCorner.getY() - pos.getY() + 1;
            tickSinceLastUpdate = 0;
        }
    }

    /**
     * Determines the farthest corner (top-right) of a rectangular screen structure
     * starting from the given anchor position (bottom-left corner) and facing direction.
     * The method ensures that the farthest corner is part of a valid rectangle of contiguous
     * ScreenBlockEntities, starting from the anchor block.
     *
     * @param level     The level (world) where the screen structure resides.
     * @param anchorPos The position of the anchor block (bottom-left corner of the screen structure).
     * @param facing    The direction the screen structure is facing.
     *                  Valid values are NORTH, SOUTH, EAST, or WEST.
     * @return The position of the farthest corner (top-right) of the rectangular screen structure.
     * @throws IllegalArgumentException If the provided facing direction is invalid.
     */
    private BlockPos getFarCorner(Level level, BlockPos anchorPos, Direction facing) {
        // Determine the perpendicular directions based on facing
        Direction perpendicular = switch (facing) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case WEST -> Direction.SOUTH;
            case EAST -> Direction.NORTH;
            default -> throw new IllegalArgumentException("Invalid facing direction");
        };

        // Calculate furthestRight by traversing in the perpendicular direction
        int furthestRight = 0;
        BlockPos currentPos = anchorPos.relative(perpendicular);
        while (level.getBlockEntity(currentPos) instanceof ScreenBlockEntity) {
            furthestRight++;
            currentPos = currentPos.relative(perpendicular);
        }

        // Calculate furthestUp by traversing upwards
        int furthestUp = 0;
        currentPos = anchorPos.above();
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
                        BlockPos checkPos = anchorPos.relative(perpendicular, x).above(y);
                        if (!checkPos.equals(anchorPos)
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
        return anchorPos.relative(perpendicular, bestRight).above(bestUp);
    }
}
