package com.nstut.simplyscreens.compat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.renderer.block.model.ModelBlockDefinition;
import org.junit.Test;
import static org.junit.Assert.*;

/** Parse the real generated pack through Minecraft 1.12's exact-key loader. */
public class NativeBlockstateVariantsTest {
    @Test public void allEighteenNativeVariantsResolve() throws Exception {
        try(InputStream stream=getClass().getResourceAsStream("/assets/simply_screens/blockstates/screen.json")) {
            assertNotNull("Generated main resources must be on the runtime classpath",stream);
            ModelBlockDefinition model=ModelBlockDefinition.parseFromReader(new InputStreamReader(stream,StandardCharsets.UTF_8));
            for(String facing:new String[]{"north","south","west","east","up","down"}) {
                for(int state=0;state<3;state++)assertNotNull(model.getVariant("facing="+facing+",state="+state));
            }
        }
    }
    @Test(expected=ModelBlockDefinition.MissingVariantException.class) public void previousSelectorOrderFailsNativeLookup() {
        ModelBlockDefinition model=ModelBlockDefinition.parseFromReader(new StringReader("{\"variants\":{\"state=0,facing=north\":{\"model\":\"simply_screens:block/screen\"}}}"));
        model.getVariant("facing=north,state=0");
    }
}
