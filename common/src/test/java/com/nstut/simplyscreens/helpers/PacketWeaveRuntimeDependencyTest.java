package com.nstut.simplyscreens.helpers;

import com.nstut.packetweave.api.TransferRegistry;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.*;

class PacketWeaveRuntimeDependencyTest {
    @Test void receiverUsesTheStandalonePublicApi() throws Exception {
        assertSame(TransferRegistry.class, MediaDownloadReceiver.class.getDeclaredField("transfers").getType());
        assertEquals("com.nstut.packetweave.api", TransferRegistry.class.getPackageName());
        assertNotEquals(codeSource(MediaDownloadReceiver.class), codeSource(TransferRegistry.class),
                "PacketWeave must have its own compile/test artifact, not be part of Screens output");
    }

    @Test void noPrivateCopyCanSatisfyTheRuntimeDependency() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.nstut.simplyscreens.internal.packetweave.api.TransferRegistry"));
    }

    @Test void receiverCannotLinkWithoutTheExternalProvider() throws Exception {
        try (URLClassLoader screensOnly = new URLClassLoader(
                new URL[]{codeSource(MediaDownloadReceiver.class)}, ClassLoader.getPlatformClassLoader())) {
            Class<?> receiver = screensOnly.loadClass(MediaDownloadReceiver.class.getName());
            NoClassDefFoundError missing = assertThrows(NoClassDefFoundError.class, receiver::getDeclaredFields);
            assertTrue(missing.getMessage().contains("com/nstut/packetweave/api/TransferRegistry"),
                    "The unresolved type must be the required external PacketWeave API");
        }
    }

    private static URL codeSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }
}
