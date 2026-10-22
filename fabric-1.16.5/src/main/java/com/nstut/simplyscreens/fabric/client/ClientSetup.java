package com.nstut.simplyscreens.fabric.client;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.client.ClientServerConfig;
import com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.network.PacketRegistries;
import net.fabricmc.api.ClientModInitializer;
import me.shedaniel.architectury.event.events.client.ClientPlayerEvent;
import me.shedaniel.architectury.registry.BlockEntityRenderers;
public final class ClientSetup implements ClientModInitializer {
    @Override public void onInitializeClient() {
        com.nstut.simplyscreens.client.testing.NativeClientAcceptance.initialize();
        ClientImageManager.initialize();
        PacketRegistries.registerS2CPackets();
        BlockEntityRenderers.registerRenderer(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> { ClientImageManager.clearCache(); ClientServerConfig.reset(); });
    }
}
