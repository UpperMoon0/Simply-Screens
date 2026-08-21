package com.nstut.simplyscreens.blocks;

import com.nstut.simplyscreens.SimplyScreens;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.jetbrains.annotations.NotNull;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;

public class ScreenBlock extends Block implements EntityBlock {

    public static final EnumProperty<Direction> FACING = EnumProperty.create("facing", Direction.class, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN);
    public static final IntegerProperty STATE = IntegerProperty.create("state", 0, 2);

    public static final int STATE_CHILD = 0;
    public static final int STATE_ANCHOR = 1;
    public static final int STATE_ERROR = 2;

    public ScreenBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(STATE, STATE_ANCHOR));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> blockStateBuilder) {
        blockStateBuilder.add(FACING, STATE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getNearestLookingDirection().getOpposite())
                .setValue(STATE, STATE_ANCHOR);
    }
    
    @Override
    protected InteractionResult useWithoutItem(BlockState blockState, Level level, BlockPos blockPos, Player player, BlockHitResult blockHitResult) {
        return InteractionResult.PASS;
    }

    @Override
    public @NotNull RenderShape getRenderShape(@NotNull BlockState pState) {
        return RenderShape.MODEL;
    }

    @Override
    public @NotNull BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new ScreenBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> blockEntityType) {
        if (state.getValue(STATE) != STATE_ANCHOR) return null;
        return level.isClientSide() ? null : (lvl, pos, blockState, blockEntity) -> {
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                screenBlockEntity.tick();
            }
        };
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && level.getServer() != null) {
            level.getServer().execute(() -> refreshScreens(level, pos, state.getValue(FACING)));
        }
    }

    public static void refreshScreens(Level level, BlockPos changedPos, Direction facing) {
        for (Direction direction : Direction.values()) refreshScreenAt(level, changedPos.relative(direction), facing);
        refreshScreenAt(level, changedPos, facing);
    }

    private static void refreshScreenAt(Level level, BlockPos pos, Direction facing) {
        if (!level.hasChunkAt(pos)) return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof ScreenBlockEntity screen && screen.getBlockState().getValue(FACING) == facing) {
            ScreenBlockEntity anchor = screen.getAnchorEntity();
            if (anchor != null) anchor.updateScreenStructure();
        }
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Return a simple cube shape for interaction
        return Block.box(0, 0, 0, 16, 16, 16);
    }
    
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Return a simple cube shape for collision
        return Block.box(0, 0, 0, 16, 16, 16);
    }
    
    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        // Return a simple cube shape for interaction
        return Block.box(0, 0, 0, 16, 16, 16);
    }

}
