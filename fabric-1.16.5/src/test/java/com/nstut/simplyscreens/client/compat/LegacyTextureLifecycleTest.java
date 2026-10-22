package com.nstut.simplyscreens.client.compat;

import com.nstut.simplyscreens.client.mixin.TextureManagerAccessor;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real native registration maps, with mocked GPU textures: no GL context is claimed. */
class LegacyTextureLifecycleTest {
    static final class Manager extends TextureManager implements TextureManagerAccessor {
        Manager() { super(mock(ResourceManager.class)); }
        @SuppressWarnings("unchecked") public Map<ResourceLocation, AbstractTexture> simplyscreens$ownedTextures() {
            try { Field field=TextureManager.class.getDeclaredField("byPath");field.setAccessible(true);return (Map<ResourceLocation, AbstractTexture>)field.get(this); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }
    @Test void manyNativeRegistrationsReleaseExactlyOnceWithoutGrowingCounters() throws Exception {
        Manager manager = new Manager();
        for (int image=0;image<100;image++) {
            DynamicTexture texture=mock(DynamicTexture.class);
            ResourceLocation location=LegacyTextureLifecycle.register(manager,"image-"+image,texture);
            assertSame(texture,manager.getTexture(location));
            LegacyTextureLifecycle.release(manager,location,texture);
            LegacyTextureLifecycle.release(manager,location,texture);
            assertNull(manager.getTexture(location));
            verify(texture,times(1)).close();verify(texture,never()).getId();
        }
        assertTrue(manager.simplyscreens$ownedTextures().isEmpty());
        Field counter=TextureManager.class.getDeclaredField("prefixRegister");counter.setAccessible(true);
        assertTrue(((Map<?,?>)counter.get(manager)).isEmpty());
    }
    @Test void aForeignReplacementIsNotDeletedAndSharedErrorClosesOnce() {
        Manager manager=new Manager();DynamicTexture error=mock(DynamicTexture.class);
        ResourceLocation location=LegacyTextureLifecycle.register(manager,"error",error);
        DynamicTexture foreign=mock(DynamicTexture.class);
        manager.simplyscreens$ownedTextures().put(location,foreign);
        LegacyTextureLifecycle.release(manager,location,error);
        assertSame(foreign,manager.getTexture(location));verify(error,never()).close();verify(foreign,never()).close();
        manager.simplyscreens$ownedTextures().put(location,error);
        LegacyTextureLifecycle.release(manager,location,error);LegacyTextureLifecycle.release(manager,location,error);
        verify(error,times(1)).close();verify(error,never()).getId();
    }
}
