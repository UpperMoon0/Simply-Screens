package com.nstut.simplyscreens.creative_tabs;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.items.ItemRegistries;
import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public class CreativeTabRegistries {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(SimplyScreens.MOD_ID, Registries.CREATIVE_MODE_TAB);

    public static final RegistrySupplier<CreativeModeTab> CREATIVE_TAB = CREATIVE_TABS.register(
            "tab",
            () -> CreativeTabRegistry.create(
                    Component.translatable("itemGroup." + SimplyScreens.MOD_ID + ".tab"),
                    () -> new ItemStack(ItemRegistries.SCREEN.get())
            )
    );

    public static void init() {
        CREATIVE_TABS.register();
    }
}