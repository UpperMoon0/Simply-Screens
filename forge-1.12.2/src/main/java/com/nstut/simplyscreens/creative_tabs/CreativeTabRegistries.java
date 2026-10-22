package com.nstut.simplyscreens.creative_tabs;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
public final class CreativeTabRegistries { public static final CreativeTabs CREATIVE_TAB=new CreativeTabs("simply_screens") { public ItemStack createIcon() { return new ItemStack(com.nstut.simplyscreens.blocks.BlockRegistries.SCREEN.get()); } }; }
