package com.nstut.simplyscreens.creative_tabs;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.items.ItemRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
public final class CreativeTabRegistries {
    public static final CreativeModeTab CREATIVE_TAB = me.shedaniel.architectury.registry.CreativeTabs.create(
        new ResourceLocation(SimplyScreens.MOD_ID, "tab"), () -> new ItemStack(ItemRegistries.SCREEN.get()));
    private CreativeTabRegistries() {}
}
