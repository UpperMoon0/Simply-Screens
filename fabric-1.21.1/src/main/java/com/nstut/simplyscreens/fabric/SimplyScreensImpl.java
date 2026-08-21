package com.nstut.simplyscreens.fabric;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.fabric.config.FabricConfig;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries;
import com.nstut.simplyscreens.items.ItemRegistries;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.ServerConfigSyncS2CPacket;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.level.storage.LevelResource;

public class SimplyScreensImpl implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricConfig.init();

        CreativeTabRegistries.CREATIVE_TABS.register();
        BlockRegistries.BLOCKS.register();
        BlockEntityRegistries.BLOCK_ENTITIES.register();
        ItemRegistries.ITEMS.register();

        SimplyScreens.initCommon();
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                SimplyScreens.initializeScreens(server.getWorldPath(LevelResource.ROOT)));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            PacketRegistries.sendToPlayer(handler.getPlayer(), new ServerConfigSyncS2CPacket(
                com.nstut.simplyscreens.Config.DISABLE_UPLOAD,
                com.nstut.simplyscreens.Config.DISABLE_URL_DOWNLOAD,
                com.nstut.simplyscreens.Config.MAX_UPLOAD_SIZE));
        });
    }
}
