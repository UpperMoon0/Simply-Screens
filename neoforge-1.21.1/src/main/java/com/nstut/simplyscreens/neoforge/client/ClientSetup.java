package com.nstut.simplyscreens.neoforge.client;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.network.PacketRegistries;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

@EventBusSubscriber(modid = SimplyScreens.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        SimplyScreens.LOGGER.info("NeoForge ClientSetup.onClientSetup called");
        System.out.println("NeoForge ClientSetup.onClientSetup called");
        
        event.enqueueWork(() -> {
            SimplyScreens.LOGGER.info("NeoForge ClientSetup enqueueWork executing");
            System.out.println("NeoForge ClientSetup enqueueWork executing");
            
            ClientImageManager.initialize();
            SimplyScreens.LOGGER.info("ClientImageManager initialized");
            System.out.println("ClientImageManager initialized");
            
            BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);
            SimplyScreens.LOGGER.info("BlockEntityRenderer registered");
            System.out.println("BlockEntityRenderer registered");
            
            // Register S2C packets for receiving on client
            PacketRegistries.registerS2CPackets();
            SimplyScreens.LOGGER.info("S2C Packets registered");
            System.out.println("S2C Packets registered");
            
            NeoForge.EVENT_BUS.addListener(ClientSetup::onClientDisconnect);
            SimplyScreens.LOGGER.info("Client disconnect listener added");
            System.out.println("Client disconnect listener added");
        });
    }

    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        SimplyScreens.LOGGER.info("Client disconnect event received");
        System.out.println("Client disconnect event received");
        ClientImageManager.clearCache();
    }
}