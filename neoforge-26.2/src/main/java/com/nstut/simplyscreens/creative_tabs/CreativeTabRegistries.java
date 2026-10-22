package com.nstut.simplyscreens.creative_tabs;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.items.ItemRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CreativeTabRegistries {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SimplyScreens.MOD_ID);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB =
            CREATIVE_TABS.register("tab", () -> dev.architectury.registry.CreativeTabRegistry.create(
                    Component.translatable("itemGroup." + SimplyScreens.MOD_ID),
                    () -> new ItemStack(ItemRegistries.SCREEN.get())));

    public static void buildContents(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() == CREATIVE_TAB.get()) ItemRegistries.ITEM_LIST.forEach(item ->
                event.accept(new ItemStack(item.get())));
    }


    private CreativeTabRegistries() {
    }
}
