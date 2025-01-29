package com.nstut.simplyscreens.client;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = SimplyScreens.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {
    @SuppressWarnings("unused")
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);
    }
}