package com.nstut.simplyscreens;

import com.nstut.simplyscreens.blocks.BlockRegistries;
import com.nstut.simplyscreens.blocks.entities.BlockEntityRegistries;
import com.nstut.simplyscreens.items.ItemRegistries;
import com.nstut.simplyscreens.network.PacketRegistries;
import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.architectury.registry.registries.DeferredRegister;

public class SimplyScreens {
    public static final String MOD_ID = "simplyscreens";
    public static final Logger LOGGER = LoggerFactory.getLogger("Simply Screens");
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(MOD_ID, Registries.CREATIVE_MODE_TAB);

    public static final RegistrySupplier<CreativeModeTab> CREATIVE_TAB = CREATIVE_TABS.register(
            "tab",
            () -> CreativeTabRegistry.create(
                    Component.translatable("itemGroup." + MOD_ID + ".tab"),
                    () -> new ItemStack(ItemRegistries.SCREEN.get())
            )
    );

    public static void init() {
        LOGGER.info("Initializing Simply Screens");

        Config.load();
        CREATIVE_TABS.register();
        BlockRegistries.init();
        BlockEntityRegistries.init();
        ItemRegistries.init();
        PacketRegistries.register();
    }
}
