package com.nstut.simplyscreens.fabric;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries;
import com.nstut.simplyscreens.items.ItemRegistries;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.ServerConfigSyncS2CPacket;
import net.fabricmc.api.ModInitializer;
import me.shedaniel.architectury.event.events.LifecycleEvent;
import me.shedaniel.architectury.event.events.CommandRegistrationEvent;
import me.shedaniel.architectury.event.events.PlayerEvent;
import net.minecraft.world.level.storage.LevelResource;

public class SimplyScreensImpl implements ModInitializer {
    @Override
    public void onInitialize() {


        BlockRegistries.BLOCKS.register();
        BlockEntityRegistries.BLOCK_ENTITIES.register();
        ItemRegistries.ITEMS.register();
        com.nstut.simplyscreens.recipes.GuideBookRecipe.register();

        SimplyScreens.initCommon();
        CommandRegistrationEvent.EVENT.register((dispatcher, dedicated) ->
                com.nstut.simplyscreens.api.ScreenCommands.register(dispatcher));
        LifecycleEvent.SERVER_STARTED.register(server ->
                SimplyScreens.initializeScreens(server.getWorldPath(LevelResource.ROOT)));
        PlayerEvent.PLAYER_JOIN.register(player -> {
            PacketRegistries.sendToPlayer(player, new ServerConfigSyncS2CPacket(
                com.nstut.simplyscreens.Config.DISABLE_UPLOAD,
                com.nstut.simplyscreens.Config.DISABLE_URL_DOWNLOAD,
                com.nstut.simplyscreens.Config.MAX_UPLOAD_SIZE));
        });
    }
}
