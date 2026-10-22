package com.nstut.simplyscreens.fabric.client;

import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.client.ClientServerConfig;
import com.nstut.simplyscreens.network.PacketRegistries;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class ClientSetup implements ClientModInitializer {
    private static ClientLevel currentClientLevel;
    @Override public void onInitializeClient() {
        com.nstut.simplyscreens.client.mixin.RenderPipelinesAccessor.simplyscreens$register(com.nstut.simplyscreens.client.renderers.ScreenColorRestore.PIPELINE);
        com.nstut.simplyscreens.client.mixin.RenderPipelinesAccessor.simplyscreens$register(com.nstut.simplyscreens.client.renderers.ScreenRenderTypes.SCREEN_PIPELINE);
        com.nstut.simplyscreens.testing.LiveJoinTestProtocol.startProbe();
        dev.architectury.event.events.client.ClientTickEvent.CLIENT_POST.register(ClientSetup::onClientTick);
        ClientImageManager.initialize();
        net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);
        PacketRegistries.registerS2CPackets();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ClientImageManager.clearCache();
            ScreenBlockEntityRenderer.clearCaches();
            ClientServerConfig.reset();
            currentClientLevel = null;
        });
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, world) -> {
            if (currentClientLevel != null && currentClientLevel != world) ScreenBlockEntityRenderer.clearLevel(currentClientLevel);
            currentClientLevel = world;
        });
    }
    private static void onClientTick(net.minecraft.client.Minecraft client) {
        if (!com.nstut.simplyscreens.testing.LiveJoinTestProtocol.isEnabled() || client.player == null || client.level == null) return;
        if (!com.nstut.simplyscreens.client.testing.UiSmokeTest.tick(client)) return;
        com.nstut.simplyscreens.testing.LiveJoinTestProtocol.markCompleted();
        if (com.nstut.simplyscreens.testing.LiveJoinTestProtocol.passed() && com.nstut.simplyscreens.testing.LiveJoinTestProtocol.markReported()) {
            com.nstut.simplyscreens.SimplyScreens.LOGGER.info(com.nstut.simplyscreens.testing.LiveJoinTestProtocol.PASS_MARKER);
            com.nstut.simplyscreens.testing.LiveJoinTestProtocol.stopClient(client::stop);
        }
    }
}
