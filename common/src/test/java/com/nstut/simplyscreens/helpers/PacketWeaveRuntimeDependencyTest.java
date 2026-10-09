package com.nstut.simplyscreens.helpers;

import com.nstut.packetweave.api.TransferRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

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
        ClassLoader screensOnly = byteIsolatedLoader(MediaDownloadReceiver.class);
        assertThrows(ClassNotFoundException.class, () -> screensOnly.loadClass(TransferRegistry.class.getName()));
        Class<?> receiver = screensOnly.loadClass(MediaDownloadReceiver.class.getName());
        assertSame(screensOnly, receiver.getClassLoader());
        assertNotSame(MediaDownloadReceiver.class, receiver);
        NoClassDefFoundError missing = assertThrows(NoClassDefFoundError.class, receiver::getDeclaredFields);
        assertTrue(missing.getMessage().contains("com/nstut/packetweave/api/TransferRegistry"),
                "The unresolved type must be the required external PacketWeave API");
    }

    @Test void theSameReceiverBytesLinkWhenThePublicProviderIsAvailable() throws Exception {
        ClassLoader withProvider = byteIsolatedLoader(MediaDownloadReceiver.class, TransferRegistry.class);
        Class<?> receiver = withProvider.loadClass(MediaDownloadReceiver.class.getName());
        Class<?> provider = withProvider.loadClass(TransferRegistry.class.getName());
        assertSame(withProvider, receiver.getClassLoader());
        assertSame(withProvider, provider.getClassLoader());
        assertNotSame(MediaDownloadReceiver.class, receiver);
        assertNotSame(TransferRegistry.class, provider);
        assertSame(provider, receiver.getDeclaredField("transfers").getType());
    }

    private static ClassLoader byteIsolatedLoader(Class<?>... types) throws IOException {
        Map<String, byte[]> definitions = new HashMap<>();
        for (Class<?> type : types) {
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            try (InputStream bytes = type.getResourceAsStream(resource)) {
                assertNotNull(bytes, "Missing compiled class resource: " + resource);
                definitions.put(type.getName(), bytes.readAllBytes());
            }
        }
        // FML uses named-module loaders, and the platform loader may delegate
        // to them. A CodeSource URL plus that parent is not an isolation boundary.
        // Define only the supplied real bytes, permit JDK classes, and reject
        // every other name even if a native loader or bootstrap can see it.
        return new ClassLoader(null) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        byte[] bytes = definitions.get(name);
                        if (bytes != null) loaded = defineClass(name, bytes, 0, bytes.length);
                        else if (name.startsWith("java.")) loaded = super.loadClass(name, false);
                        else throw new ClassNotFoundException(name);
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
    }

    private static URL codeSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }
}
