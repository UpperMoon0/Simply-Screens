package com.nstut.simplyscreens.neoforge;

import com.nstut.packetweave.api.TransferRegistry;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketWeaveNativeProviderTest {
    @Test void realNativeProviderSatisfiesTheLoaderAndOwnsThePublicApi() throws Exception {
        assertTrue(ModList.get().isLoaded("packetweave"),
                "NeoForge JUnit must discover the native provider, not just compile-only API classes");
        Class<?> entrypoint = Class.forName("com.nstut.packetweave.neoforge.PacketWeaveNeoForge");
        assertEquals(entrypoint.getProtectionDomain().getCodeSource().getLocation(),
                TransferRegistry.class.getProtectionDomain().getCodeSource().getLocation(),
                "The native provider must supply the API without a second API artifact on test runtime");
    }
}
