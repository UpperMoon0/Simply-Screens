package com.nstut.simplyscreens.compat;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
public class LegacyImageTest {
 @Test public void modernAbgrMapsExactlyToNativeArgb() { try(LegacyImage image=new LegacyImage(2,1,false)) { image.setPixelRGBA(0,0,0x80402010);assertEquals(0x80102040,image.buffered().getRGB(0,0));assertEquals(2,image.getWidth());assertEquals(1,image.getHeight()); } }
 @Test public void transparentPixelsRoundTrip()throws Exception { BufferedImage source=new BufferedImage(2,1,BufferedImage.TYPE_INT_ARGB);source.setRGB(0,0,0x80102030);source.setRGB(1,0,0xffc03010);ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(source,"png",output);try(LegacyImage image=LegacyImage.read(new ByteArrayInputStream(output.toByteArray()))) { assertEquals(source.getRGB(0,0),image.buffered().getRGB(0,0));assertEquals(source.getRGB(1,0),image.buffered().getRGB(1,0)); } }
 @Test(expected=java.io.IOException.class) public void invalidImageRejected()throws Exception { LegacyImage.read(new ByteArrayInputStream(new byte[]{1,2,3})); }
}
