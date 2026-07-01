package com.nstut.simplyscreens.neoforge;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.network.PacketRegistries;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = SimplyScreens.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = SimplyScreens.MOD_ID, value = Dist.CLIENT)
public final class SimplyScreensClient {
    public SimplyScreensClient(ModContainer container) {
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientImageManager.initialize();
            BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);
            PacketRegistries.registerS2CPackets();
        });
    }
}
