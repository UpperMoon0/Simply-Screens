package com.nstut.simplyscreens.client.compat;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.client.mixin.TextureManagerAccessor;
import com.nstut.simplyscreens.compat.OwnedTextureReleaser;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

/** 1.16 release(location) deletes only a GL name, so never use it for owned media. */
public final class LegacyTextureLifecycle {
    private LegacyTextureLifecycle() {}
    public static ResourceLocation register(TextureManager manager, String key, DynamicTexture texture) {
        ResourceLocation location = new ResourceLocation(SimplyScreens.MOD_ID, "media/" + key);
        manager.register(location, texture); // No unbounded UUID prefix-counter registrations.
        return location;
    }
    public static void release(TextureManager manager, ResourceLocation location, DynamicTexture texture) {
        if (texture == null) return;
        if (location == null) { texture.close(); return; }
        OwnedTextureReleaser.release(((TextureManagerAccessor)manager).simplyscreens$ownedTextures(),
                location, texture, texture::close);
    }
}
