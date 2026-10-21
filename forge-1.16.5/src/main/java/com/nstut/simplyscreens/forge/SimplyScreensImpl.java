package com.nstut.simplyscreens.forge;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries;
import com.nstut.simplyscreens.items.ItemRegistries;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.ServerConfigSyncS2CPacket;
import me.shedaniel.architectury.platform.forge.EventBuses;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraft.world.level.storage.LevelResource;

@Mod(SimplyScreens.MOD_ID)
public class SimplyScreensImpl {
    public SimplyScreensImpl() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        EventBuses.registerModEventBus(SimplyScreens.MOD_ID, modEventBus);


        BlockRegistries.BLOCKS.register();
        BlockEntityRegistries.BLOCK_ENTITIES.register();
        ItemRegistries.ITEMS.register();
        com.nstut.simplyscreens.recipes.GuideBookRecipe.register();

        modEventBus.addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.RegisterCommandsEvent event) ->
                com.nstut.simplyscreens.api.ScreenCommands.register(event.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener((FMLServerStartedEvent event) ->
                SimplyScreens.initializeScreens(event.getServer().getWorldPath(LevelResource.ROOT)));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player) {
                PacketRegistries.sendToPlayer(player, new ServerConfigSyncS2CPacket(
                    com.nstut.simplyscreens.Config.DISABLE_UPLOAD,
                    com.nstut.simplyscreens.Config.DISABLE_URL_DOWNLOAD,
                    com.nstut.simplyscreens.Config.MAX_UPLOAD_SIZE));
            }
        });
    }

    public void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SimplyScreens::initCommon);
    }
}
