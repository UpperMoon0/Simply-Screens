package com.nstut.simplyscreens.recipes;

import com.nstut.simplyscreens.SimplyScreens;
import me.shedaniel.architectury.registry.DeferredRegister;
import me.shedaniel.architectury.registry.RegistrySupplier;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleRecipeSerializer;
import net.minecraft.world.level.Level;

/** Native recipe because Patchouli 1.16 has no shaped_book_recipe serializer. */
public final class GuideBookRecipe extends CustomRecipe {
    private static final ResourceLocation BOOK_ITEM = new ResourceLocation("patchouli", "guide_book");
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(
            SimplyScreens.MOD_ID, Registry.RECIPE_SERIALIZER_REGISTRY);
    private static final RegistrySupplier<RecipeSerializer<GuideBookRecipe>> SERIALIZER = SERIALIZERS.register(
            "guide_book", () -> new SimpleRecipeSerializer<>(GuideBookRecipe::new));
    public GuideBookRecipe(ResourceLocation id) { super(id); }
    public static void register() { SERIALIZERS.register(); }
    @Override public boolean matches(CraftingContainer grid, Level level) {
        if (createBook().isEmpty()) return false;
        boolean book = false, quartz = false;
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (stack.isEmpty()) continue;
            if (!book && stack.getItem() == Items.BOOK) book = true;
            else if (!quartz && stack.getItem() == Items.QUARTZ) quartz = true;
            else return false;
        }
        return book && quartz;
    }
    @Override public ItemStack assemble(CraftingContainer grid) { return createBook(); }
    @Override public ItemStack getResultItem() { return createBook(); }
    @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= 2; }
    @Override public RecipeSerializer<?> getSerializer() { return SERIALIZER.get(); }
    private static ItemStack createBook() {
        Item item = Registry.ITEM.getOptional(BOOK_ITEM).orElse(null);
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        ItemStack result = new ItemStack(item);
        result.getOrCreateTag().putString("patchouli:book", SimplyScreens.MOD_ID + ":guide");
        return result;
    }
}
