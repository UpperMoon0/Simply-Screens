package com.nstut.simplyscreens.forge.client;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.client.ClientServerConfig;
import com.nstut.simplyscreens.client.testing.UiSmokeTest;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.testing.LiveJoinTestProtocol;
import dev.architectury.event.events.client.ClientTickEvent;
import net.minecraft.client.Minecraft;
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
        ClientTickEvent.CLIENT_POST.register(ClientSetup::onClientTick);
        LiveJoinTestProtocol.startProbe();
        event.enqueueWork(() -> {
            ClientImageManager.initialize();
            PacketRegistries.registerS2CPackets();
            BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);
            MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientDisconnect);
        });
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

    public static void onClientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientImageManager.clearCache();
        ClientServerConfig.reset();
    }
}
