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
            CREATIVE_TABS.register("tab", () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup." + SimplyScreens.MOD_ID))
                    .icon(() -> new ItemStack(ItemRegistries.SCREEN.get()))
                    .displayItems((params, output) -> ItemRegistries.ITEM_LIST.forEach(item -> output.accept(item.get())))
                    .build());

    private CreativeTabRegistries() {
    }
}
