package com.nstut.simplyscreens.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;

import org.jetbrains.annotations.NotNull;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntity;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class ScreenBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = DirectionProperty.create("facing", Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN);
    public static final IntegerProperty STATE = IntegerProperty.create("state", 0, 2);

    public static final int STATE_CHILD = 0;
    public static final int STATE_ANCHOR = 1;
    public static final int STATE_ERROR = 2;

    public ScreenBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .noOcclusion()
                .strength(3.5F, 6.0F) // Hardness 3.5 (like iron), resistance 6.0 (like iron)
                .requiresCorrectToolForDrops() // Requires proper tool to drop
                .sound(SoundType.METAL)); // Metal sound type
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
    public @NotNull RenderShape getRenderShape(@NotNull BlockState pState) {
        return RenderShape.MODEL;
    }

    @Override
    public @NotNull BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new ScreenBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> blockEntityType) {
        return level.isClientSide ? null : (lvl, pos, blockState, blockEntity) -> {
            if (blockEntity instanceof ScreenBlockEntity screenBlockEntity) {
                screenBlockEntity.tick();
            }
        };
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getServer() != null) {
            level.getServer().execute(() -> refreshScreens(level, pos, state.getValue(FACING)));
        }
    }

    private static void refreshScreens(Level level, BlockPos changedPos, Direction facing) {
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
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (state.getBlock() != newState.getBlock() && !level.isClientSide) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            BlockPos oldAnchorPos = blockEntity instanceof ScreenBlockEntity screen ? screen.getAnchorPos() : null;
            if (blockEntity instanceof ScreenBlockEntity screen && screen.isAnchor()) {
                com.nstut.simplyscreens.ScreenRegistry.unregisterScreen(level, pos, screen.getScreenId());
                screen.findNewAnchor();
            }
            if (level.getServer() != null) level.getServer().execute(() -> {
                if (oldAnchorPos != null) refreshScreenAt(level, oldAnchorPos, state.getValue(FACING));
                refreshScreens(level, pos, state.getValue(FACING));
            });
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Block.box(0, 0, 0, 16, 16, 16);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Block.box(0, 0, 0, 16, 16, 16);
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Block.box(0, 0, 0, 16, 16, 16);
    }
}
