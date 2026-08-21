package com.nstut.simplyscreens.neoforge;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries;
import com.nstut.simplyscreens.items.ItemRegistries;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.ServerConfigSyncS2CPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.PlayerLoggedInEvent;
import net.minecraft.world.level.storage.LevelResource;

@Mod(SimplyScreens.MOD_ID)
public class SimplyScreensImpl {
    public SimplyScreensImpl(IEventBus modEventBus) {
        CreativeTabRegistries.CREATIVE_TABS.register();
        BlockRegistries.BLOCKS.register();
        BlockEntityRegistries.BLOCK_ENTITIES.register();
        ItemRegistries.ITEMS.register();

        modEventBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                SimplyScreens.initializeScreens(event.getServer().getWorldPath(LevelResource.ROOT)));
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
    }

    private void onPlayerLoggedIn(PlayerLoggedInEvent event) {
        if (event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player) {
            PacketRegistries.sendToPlayer(player, new ServerConfigSyncS2CPacket(
                com.nstut.simplyscreens.Config.DISABLE_UPLOAD,
                com.nstut.simplyscreens.Config.DISABLE_URL_DOWNLOAD,
                com.nstut.simplyscreens.Config.MAX_UPLOAD_SIZE));
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SimplyScreens::initCommon);
    }
}
