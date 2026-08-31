package com.nstut.simplyscreens.neoforge.client;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.blocks.entities.ScreenBlockEntityClientUpdates;
import com.nstut.simplyscreens.client.compat.sable.ClientScreenSpatialResolver;
import com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.client.ClientServerConfig;
import com.nstut.simplyscreens.client.testing.UiSmokeTest;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.ClientPacketHandler;
import com.nstut.simplyscreens.testing.LiveJoinTestProtocol;
import dev.architectury.event.events.client.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

@EventBusSubscriber(modid = SimplyScreens.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        SimplyScreens.LOGGER.info("NeoForge ClientSetup.onClientSetup called");

        ClientTickEvent.CLIENT_POST.register(ClientSetup::onClientTick);
        LiveJoinTestProtocol.startProbe();

        event.enqueueWork(() -> {
            SimplyScreens.LOGGER.info("NeoForge ClientSetup enqueueWork executing");
            
            ClientImageManager.initialize();
            SimplyScreens.LOGGER.info("ClientImageManager initialized");

            ScreenBlockEntityClientUpdates.register(ClientPacketHandler::handleBlockEntityUpdate);
            
            BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);
            SimplyScreens.LOGGER.info("BlockEntityRenderer registered");
            
            // Register S2C packets for receiving on client
            PacketRegistries.registerS2CPackets();
            SimplyScreens.LOGGER.info("S2C Packets registered");
            
            NeoForge.EVENT_BUS.addListener(ClientSetup::onClientDisconnect);
            NeoForge.EVENT_BUS.addListener(ClientSetup::onRenderLevelStage);
            SimplyScreens.LOGGER.info("Client disconnect listener added");
        });
    }

    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        SimplyScreens.LOGGER.info("Client disconnect event received");
        ClientImageManager.clearCache();
        ClientServerConfig.reset();
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            ClientScreenSpatialResolver.beginRenderFrame();
        }
    }

    private static void onClientTick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        if (LiveJoinTestProtocol.isEnabled() && !UiSmokeTest.tick(client)) return;
        LiveJoinTestProtocol.markCompleted();
        finishLiveJoinTest(client);
    }

    private static void finishLiveJoinTest(Minecraft client) {
        if (LiveJoinTestProtocol.isEnabled()
                && LiveJoinTestProtocol.passed()
                && LiveJoinTestProtocol.markReported()) {
            SimplyScreens.LOGGER.info(LiveJoinTestProtocol.PASS_MARKER);
            LiveJoinTestProtocol.stopClient(client::stop);
        }
    }
}
