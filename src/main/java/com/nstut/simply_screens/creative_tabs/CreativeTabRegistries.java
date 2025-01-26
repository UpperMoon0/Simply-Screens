package com.nstut.simply_screens.creative_tabs;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import com.nstut.simply_screens.SimplyScreens;
import com.nstut.simply_screens.items.ItemRegistries;

public class CreativeTabRegistries {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SimplyScreens.MOD_ID);
    public static final RegistryObject<CreativeModeTab> SIMPLY_SCREEN_TAB = CREATIVE_MODE_TABS.register(SimplyScreens.MOD_ID, () -> CreativeModeTab.builder()
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> ItemRegistries.SCREEN.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                for (RegistryObject<Item> i : ItemRegistries.ITEM_SET) {
                    output.accept(i.get());
                }
            })
            .title(Component.translatable("itemGroup.simplyscreens"))
            .build());
}
