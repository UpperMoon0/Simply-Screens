package com.nstut.simplyscreens.compat;
public final class LegacyDynamicTexture extends net.minecraft.client.renderer.texture.DynamicTexture implements AutoCloseable {
 private LegacyImage pixels;
 public LegacyDynamicTexture(LegacyImage image) { super(image.buffered());pixels=image; }
 public LegacyImage getPixels() { return pixels; }
 @Override public void deleteGlTexture() { super.deleteGlTexture();if(pixels!=null)pixels.close();pixels=null; }
 public void close() { deleteGlTexture(); }
}
