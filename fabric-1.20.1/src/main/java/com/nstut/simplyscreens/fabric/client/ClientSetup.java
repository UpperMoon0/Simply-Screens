package com.nstut.simplyscreens.fabric.client;

import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public class ClientSetup implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientImageManager.initialize();
        BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientImageManager.clearCache());
    }
}