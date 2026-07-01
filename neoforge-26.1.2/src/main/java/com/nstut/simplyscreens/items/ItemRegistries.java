package com.nstut.simplyscreens.items;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.blocks.BlockRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

public final class ItemRegistries {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SimplyScreens.MOD_ID);
    public static final DeferredItem<BlockItem> SCREEN = ITEMS.registerSimpleBlockItem("screen", BlockRegistries.SCREEN);
    public static final List<DeferredItem<? extends Item>> ITEM_LIST = List.of(SCREEN);

    private ItemRegistries() {
    }
}
