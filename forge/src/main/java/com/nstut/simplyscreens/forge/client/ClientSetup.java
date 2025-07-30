package com.nstut.simplyscreens.forge.client;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = SimplyScreens.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientImageManager.initialize();
            BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);
            MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientDisconnect);
        });
    }

    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientImageManager.clearCache();
    }
}