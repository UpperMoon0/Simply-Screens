package com.nstut.simplyscreens.neoforge;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.client.ClientServerConfig;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.testing.LiveJoinTestProtocol;
import dev.architectury.event.events.client.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = SimplyScreens.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = SimplyScreens.MOD_ID, value = Dist.CLIENT)
public final class SimplyScreensClient {
    public SimplyScreensClient(ModContainer container) {
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        ClientTickEvent.CLIENT_POST.register(SimplyScreensClient::onClientTick);
        LiveJoinTestProtocol.startProbe();
        event.enqueueWork(() -> {
            ClientImageManager.initialize();
            BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);
            PacketRegistries.registerS2CPackets();
            NeoForge.EVENT_BUS.addListener(SimplyScreensClient::clientDisconnect);
        });
    }

    private static void onClientTick(Minecraft client) {
        if (client.player != null && client.level != null) {
            LiveJoinTestProtocol.markCompleted();
            finishLiveJoinTest(client);
        }
    }

    private static void finishLiveJoinTest(Minecraft client) {
        if (LiveJoinTestProtocol.isEnabled()
                && LiveJoinTestProtocol.passed()
                && LiveJoinTestProtocol.markReported()) {
            SimplyScreens.LOGGER.info(LiveJoinTestProtocol.PASS_MARKER);
            LiveJoinTestProtocol.stopClient(client::stop);
        }
    }

    private static void clientDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientImageManager.clearCache();
        ClientServerConfig.reset();
    }
}
