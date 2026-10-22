package com.nstut.simplyscreens.client.compat;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.compat.LegacyDynamicTexture;
import com.nstut.simplyscreens.compat.OwnedTextureReleaser;
import java.util.Map;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

/** Forge's mapped reflection bridge removes our registration without deleting a stale GL ID. */
public final class LegacyTextureLifecycle {
    private LegacyTextureLifecycle() {}
    public static ResourceLocation register(TextureManager manager, String key, LegacyDynamicTexture texture) {
        ResourceLocation location = new ResourceLocation(SimplyScreens.MOD_ID, "media/" + key);
        manager.loadTexture(location, texture);
        return location;
    }
    public static void release(TextureManager manager, ResourceLocation location, LegacyDynamicTexture texture) {
        if (texture == null) return;
        if (location == null) { texture.close(); return; }
        Map<ResourceLocation, ITextureObject> textures = ObfuscationReflectionHelper.getPrivateValue(
                TextureManager.class, manager, "mapTextureObjects", "field_110585_a");
        OwnedTextureReleaser.release(textures, location, texture, texture::close);
    }
}
