package org.nstut.simplyscreens.items;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.nstut.simplyscreens.SimplyScreens;
import org.nstut.simplyscreens.blocks.BlockRegistries;

import java.util.HashSet;
import java.util.Set;

public class ItemRegistries {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, SimplyScreens.MOD_ID);

    public static final RegistryObject<Item> SCREEN = ITEMS.register("screen", () -> new BlockItem(BlockRegistries.SCREEN.get(), new Item.Properties()));


    public static final Set<RegistryObject<Item>> ITEM_SET;

    static {
        ITEM_SET = new HashSet<>(ITEMS.getEntries());
    }
}
