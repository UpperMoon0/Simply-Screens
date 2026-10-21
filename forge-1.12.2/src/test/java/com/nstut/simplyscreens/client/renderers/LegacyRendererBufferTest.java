package com.nstut.simplyscreens.client.renderers;

import java.nio.FloatBuffer;
import org.junit.Test;
import org.lwjgl.BufferChecks;
import org.lwjgl.BufferUtils;
import static org.junit.Assert.*;

/** Real LWJGL 2 capacity checks, without claiming a live GL context. */
public class LegacyRendererBufferTest {
    @Test public void colorQueryHasNativeRequiredCapacityButRestoresFourValues() {
        FloatBuffer buffer=ScreenBlockEntityRenderer.colorQueryBuffer();
        BufferChecks.checkBuffer(buffer,16);
        assertTrue(buffer.isDirect());
        assertEquals(16,buffer.remaining());
        for(int i=0;i<4;i++)buffer.put(i,(i+1)/4f);
        for(int i=0;i<4;i++)assertEquals((i+1)/4f,buffer.get(i),0f);
    }
    @Test(expected=IllegalArgumentException.class) public void previousFourElementAllocationFailsNativeCheck() {
        BufferChecks.checkBuffer(BufferUtils.createFloatBuffer(4),16);
    }
}
