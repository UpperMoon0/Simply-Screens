package com.nstut.simplyscreens.blocks;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.client.screens.ImageLoadScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class ClientScreenBlock extends ScreenBlock {
    public ClientScreenBlock() {
        SimplyScreens.LOGGER.info("ClientScreenBlock constructor called");
        System.out.println("ClientScreenBlock constructor called");
    }
    
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        SimplyScreens.LOGGER.info("ClientScreenBlock.use called at pos: " + pos + " on side: " + (level.isClientSide ? "client" : "server"));
        System.out.println("ClientScreenBlock.use called at pos: " + pos + " on side: " + (level.isClientSide ? "client" : "server"));
        SimplyScreens.LOGGER.info("ClientScreenBlock.use - player: " + player + ", hand: " + hand + ", hit: " + hit);
        System.out.println("ClientScreenBlock.use - player: " + player + ", hand: " + hand + ", hit: " + hit);
        SimplyScreens.LOGGER.info("ClientScreenBlock.use - state: " + state);
        System.out.println("ClientScreenBlock.use - state: " + state);
        
        if (level.isClientSide) {
            SimplyScreens.LOGGER.info("ClientScreenBlock.use - isClientSide is TRUE, opening ImageLoadScreen");
            System.out.println("ClientScreenBlock.use - isClientSide is TRUE, opening ImageLoadScreen");
            SimplyScreens.LOGGER.info("ClientScreenBlock.use - Minecraft.getInstance(): " + Minecraft.getInstance());
            System.out.println("ClientScreenBlock.use - Minecraft.getInstance(): " + Minecraft.getInstance());
            SimplyScreens.LOGGER.info("ClientScreenBlock.use - Creating ImageLoadScreen with pos: " + pos);
            System.out.println("ClientScreenBlock.use - Creating ImageLoadScreen with pos: " + pos);
            Minecraft.getInstance().setScreen(new ImageLoadScreen(pos));
            SimplyScreens.LOGGER.info("ClientScreenBlock.use - ImageLoadScreen set, returning InteractionResult.SUCCESS");
            System.out.println("ClientScreenBlock.use - ImageLoadScreen set, returning InteractionResult.SUCCESS");
            return InteractionResult.SUCCESS;
        }
        
        SimplyScreens.LOGGER.info("ClientScreenBlock.use - isClientSide is FALSE, returning InteractionResult.PASS");
        System.out.println("ClientScreenBlock.use - isClientSide is FALSE, returning InteractionResult.PASS");
        return InteractionResult.PASS;
    }
}