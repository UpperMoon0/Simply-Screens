package com.nstut.simplyscreens.compat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.renderer.block.model.ModelBlockDefinition;
import net.minecraft.client.renderer.block.model.Variant;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;
import static org.junit.Assert.*;

/** Parse the real generated pack through Minecraft 1.12's exact-key loader. */
public class NativeBlockstateVariantsTest {
    @Test public void allEighteenNativeVariantsResolve() throws Exception {
        try(InputStream stream=getClass().getResourceAsStream("/assets/simply_screens/blockstates/screen.json")) {
            assertNotNull("Generated main resources must be on the runtime classpath",stream);
            ModelBlockDefinition model=ModelBlockDefinition.parseFromReader(new InputStreamReader(stream,StandardCharsets.UTF_8));
            for(String facing:new String[]{"north","south","west","east","up","down"}) {
                for(int state=0;state<3;state++) {
                    for(Variant variant:model.getVariant("facing="+facing+",state="+state).getVariantList()) {
                        ResourceLocation location=variant.getModelLocation();
                        String expected="block/"+(state==0?"screen":state==1?"screen_anchor":"screen_error");
                        assertEquals(expected,location.getPath());
                        String resource="/assets/"+location.getNamespace()+"/models/"+location.getPath()+".json";
                        try(InputStream packaged=getClass().getResourceAsStream(resource)) {
                            assertNotNull("Native prefixed model must be packaged: "+resource,packaged);
                        }
                    }
                }
            }
        }
    }
    @Test public void previousModernModelPrefixResolvesToMissingNativeResource() {
        ModelBlockDefinition model=ModelBlockDefinition.parseFromReader(new StringReader("{\"variants\":{\"facing=north,state=0\":{\"model\":\"simply_screens:block/screen\"}}}"));
        ResourceLocation location=model.getVariant("facing=north,state=0").getVariantList().get(0).getModelLocation();
        assertEquals("block/block/screen",location.getPath());
        assertNull(getClass().getResource("/assets/simply_screens/models/"+location.getPath()+".json"));
    }
    @Test(expected=ModelBlockDefinition.MissingVariantException.class) public void previousSelectorOrderFailsNativeLookup() {
        ModelBlockDefinition model=ModelBlockDefinition.parseFromReader(new StringReader("{\"variants\":{\"state=0,facing=north\":{\"model\":\"simply_screens:block/screen\"}}}"));
        model.getVariant("facing=north,state=0");
    }
}
