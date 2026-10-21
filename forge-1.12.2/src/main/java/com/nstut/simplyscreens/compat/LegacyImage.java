package com.nstut.simplyscreens.compat;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
/** Java image pixels with the native-era ARGB storage and modern ABGR API. */
public final class LegacyImage implements AutoCloseable {
 private BufferedImage image;
 public LegacyImage(int width,int height,boolean clear) { image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB); }
 private LegacyImage(BufferedImage image) { this.image=image; }
 public static LegacyImage read(InputStream stream)throws IOException { BufferedImage decoded=ImageIO.read(stream);if(decoded==null)throw new IOException("Invalid image data");return new LegacyImage(decoded); }
 public int getWidth() { return image.getWidth(); }public int getHeight() { return image.getHeight(); }
 public void setPixelRGBA(int x,int y,int abgr) { image.setRGB(x,y,(abgr&0xff00ff00)|((abgr&0xff)<<16)|((abgr>>>16)&0xff)); }
 public BufferedImage buffered() { return image; }
 public void close() { if(image!=null)image.flush();image=null; }
}
