package com.nstut.simplyscreens.forge.client;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.client.ClientServerConfig;
import com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer;
import com.nstut.simplyscreens.helpers.ClientImageManager;
import com.nstut.simplyscreens.network.PacketRegistries;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
@Mod.EventBusSubscriber(modid=SimplyScreens.MOD_ID,bus=Mod.EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        com.nstut.simplyscreens.client.testing.NativeClientAcceptance.initialize();
        event.enqueueWork(() -> {
            ClientImageManager.initialize();PacketRegistries.registerS2CPackets();
            ClientRegistry.bindTileEntityRenderer(BlockEntityRegistries.SCREEN.get(),ScreenBlockEntityRenderer::new);
            MinecraftForge.EVENT_BUS.addListener(ClientSetup::disconnect);
        });
    }
    private static void disconnect(ClientPlayerNetworkEvent.LoggedOutEvent event) { ClientImageManager.clearCache();ClientServerConfig.reset(); }
}
