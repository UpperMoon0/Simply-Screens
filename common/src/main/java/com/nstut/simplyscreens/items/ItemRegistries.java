package com.nstut.simplyscreens.items;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

public class ItemRegistries {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(SimplyScreens.MOD_ID, Registries.ITEM);

    public static final RegistrySupplier<Item> SCREEN = ITEMS.register("screen", () -> new BlockItem(BlockRegistries.SCREEN.get(), new Item.Properties()));

    public static final List<RegistrySupplier<Item>> ITEM_LIST = new ArrayList<>();

    static {
        ITEM_LIST.add(SCREEN);
    }

}
