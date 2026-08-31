package com.nstut.simplyscreens.fabric.client;

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
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public class ClientSetup implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientImageManager.initialize();
        ScreenBlockEntityClientUpdates.register(ClientPacketHandler::handleBlockEntityUpdate);
        BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);
        WorldRenderEvents.START.register(context -> {
            ClientScreenSpatialResolver.beginRenderFrame();
            ScreenBlockEntityRenderer.beginRenderFrame();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientImageManager.clearCache();
            ClientScreenSpatialResolver.clearCaches();
            ScreenBlockEntityRenderer.clearCaches();
            ClientServerConfig.reset();
        });

        // Register S2C packets on client side only
        PacketRegistries.registerS2CPackets();

        ClientTickEvent.CLIENT_POST.register(ClientSetup::onClientTick);
        LiveJoinTestProtocol.startProbe();
    }

    private static void onClientTick(net.minecraft.client.Minecraft client) {
        if (client.player == null || client.level == null) return;
        if (LiveJoinTestProtocol.isEnabled() && !UiSmokeTest.tick(client)) return;
        LiveJoinTestProtocol.markCompleted();
        finishLiveJoinTest(client);
    }

    private static void finishLiveJoinTest(net.minecraft.client.Minecraft client) {
        if (LiveJoinTestProtocol.isEnabled()
                && LiveJoinTestProtocol.passed()
                && LiveJoinTestProtocol.markReported()) {
            SimplyScreens.LOGGER.info(LiveJoinTestProtocol.PASS_MARKER);
            LiveJoinTestProtocol.stopClient(client::stop);
        }
    }
}
