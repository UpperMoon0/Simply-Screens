package com.nstut.simply_screens.blocks;

import com.mojang.logging.LogUtils;
import com.nstut.simply_screens.client.screens.ImageUploadScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jetbrains.annotations.NotNull;
import com.nstut.simply_screens.blocks.entities.ScreenBlockEntity;
import org.slf4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;

public class ScreenBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final IntegerProperty STATE = IntegerProperty.create("state", 0, 2);

    // Define constants for the states
    public static final int STATE_CHILD = 0;   // Represents a "Child" block
    public static final int STATE_ANCHOR = 1;  // Represents an "Anchor" block
    public static final int STATE_ERROR = 2;  // Represents a "Broken" block

    private static final Logger LOGGER = LogUtils.getLogger();

    public ScreenBlock() {
        super(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK).noOcclusion());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(STATE, STATE_ANCHOR)); // Default to Anchor
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> blockStateBuilder) {
        blockStateBuilder.add(FACING, STATE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(STATE, STATE_ANCHOR); // Set as Anchor when placed
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState pState) {
        return RenderShape.MODEL;
    }

    @Override
    public @NotNull BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new ScreenBlockEntity(pos, state);
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NotNull InteractionResult use(@NotNull BlockState state, Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull InteractionHand hand, @NotNull BlockHitResult hit) {
        // Open the ImageUploadScreen when the player interacts with the block
        if (level.isClientSide) {
            Minecraft.getInstance().setScreen(new ImageUploadScreen(pos));
        }
        return InteractionResult.SUCCESS;
    }

    // This method adds the BlockEntityTicker to the block entity
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> blockEntityType) {
        return level.isClientSide ? null : (lvl, pos, blockState, blockEntity) -> {
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                screenBlockEntity.tick();
            }
        };
    }

    /**
     * Helper method to change the state of the block.
     */
    public void setBlockState(Level level, BlockPos pos, int newState) {
        BlockState currentState = level.getBlockState(pos);
        if (currentState.getBlock() instanceof ScreenBlock &&
                (newState == STATE_CHILD || newState == STATE_ANCHOR || newState == STATE_ERROR)) {
            level.setBlock(pos, currentState.setValue(STATE, newState), 3);
        } else {
            LOGGER.warn("Invalid state change attempted for block at {}: {}", pos, newState);
        }
    }
}
