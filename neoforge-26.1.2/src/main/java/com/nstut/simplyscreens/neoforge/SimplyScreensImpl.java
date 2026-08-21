package com.nstut.simplyscreens.neoforge;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.creative_tabs.CreativeTabRegistries;
import com.nstut.simplyscreens.items.ItemRegistries;
import com.nstut.simplyscreens.network.PacketRegistries;
import com.nstut.simplyscreens.network.ServerConfigSyncS2CPacket;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;

@Mod(SimplyScreens.MOD_ID)
public final class SimplyScreensImpl {
    public SimplyScreensImpl(IEventBus modBus, ModContainer container) {
        CreativeTabRegistries.CREATIVE_TABS.register(modBus);
        BlockRegistries.BLOCKS.register(modBus);
        BlockEntityRegistries.BLOCK_ENTITIES.register(modBus);
        ItemRegistries.ITEMS.register(modBus);
        modBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SimplyScreens::initCommon);
    }

    @SubscribeEvent
    public void serverStarting(ServerStartingEvent event) {
        SimplyScreens.initializeScreens(event.getServer().getWorldPath(LevelResource.ROOT));
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            PacketRegistries.sendToPlayer(player, new ServerConfigSyncS2CPacket(
                com.nstut.simplyscreens.Config.DISABLE_UPLOAD,
                com.nstut.simplyscreens.Config.DISABLE_URL_DOWNLOAD,
                com.nstut.simplyscreens.Config.MAX_UPLOAD_SIZE));
        }
    }
}
