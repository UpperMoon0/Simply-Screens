package com.nstut.simplyscreens.helpers;

import com.nstut.packetweave.api.TransferRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PacketWeaveRuntimeDependencyTest {
    @TempDir Path directory;

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
        runLinkageProbe(false);
    }

    @Test void theSameReceiverBytesLinkWhenThePublicProviderIsAvailable() throws Exception {
        runLinkageProbe(true);
    }

    @Test void probeCrashesTimeoutsAndMissingMarkersPreserveDiagnostics() {
        AssertionError crash = assertThrows(AssertionError.class,
                () -> assertProbeSucceeded(false, true, 1, "PACKETWEAVE_MISSING_OK\nstartup failure detail"));
        assertTrue(crash.getMessage().contains("startup failure detail"));
        AssertionError timeout = assertThrows(AssertionError.class,
                () -> assertProbeSucceeded(false, false, -1, "timeout diagnostic detail"));
        assertTrue(timeout.getMessage().contains("timeout diagnostic detail"));
        AssertionError noMarker = assertThrows(AssertionError.class,
                () -> assertProbeSucceeded(true, true, 0, "unexpected clean exit detail"));
        assertTrue(noMarker.getMessage().contains("unexpected clean exit detail"));
    }

    private void runLinkageProbe(boolean includeProvider) throws Exception {
        // FML's ClassLoadingGuardian deliberately replaces mod classes defined
        // in other loaders with empty classes. Keep that protection enabled and
        // test byte-level linkage in a separate, plain JVM instead.
        Path classes = Files.createDirectories(directory.resolve("classes"));
        copyClassBytes(classes, LinkageProbe.class);
        copyClassBytes(classes, MediaDownloadReceiver.class);
        if (includeProvider) copyClassBytes(classes, TransferRegistry.class);

        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path log = directory.resolve("linkage-probe.log");
        Process process = new ProcessBuilder(java.toString(), "-cp", classes.toString(),
                LinkageProbe.class.getName(), Boolean.toString(includeProvider))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            String output = Files.readString(log);
            assertProbeSucceeded(includeProvider, finished, finished ? process.exitValue() : -1, output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS),
                        "Isolated linkage JVM could not be terminated:\n" + Files.readString(log));
            }
        }
    }

    private static void assertProbeSucceeded(boolean includeProvider, boolean finished, int exitCode, String output) {
        assertTrue(finished, "Isolated linkage JVM timed out:\n" + output);
        assertEquals(0, exitCode, output);
        assertTrue(output.contains(includeProvider ? "PACKETWEAVE_PRESENT_OK" : "PACKETWEAVE_MISSING_OK"), output);
    }

    private static void copyClassBytes(Path destination, Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        Path output = destination.resolve(resource);
        Files.createDirectories(output.getParent());
        try (InputStream bytes = type.getResourceAsStream("/" + resource)) {
            assertNotNull(bytes, "Missing compiled class resource: " + resource);
            Files.copy(bytes, output);
        }
    }

    /** JDK-only entrypoint: its subprocess classpath contains only the explicit fixture bytes. */
    public static final class LinkageProbe {
        public static void main(String[] arguments) throws Exception {
            boolean includeProvider = Boolean.parseBoolean(arguments[0]);
            String receiverName = "com.nstut.simplyscreens.helpers.MediaDownloadReceiver";
            String providerName = "com.nstut.packetweave.api.TransferRegistry";
            ClassLoader loader = LinkageProbe.class.getClassLoader();
            Class<?> receiver = Class.forName(receiverName, false, loader);
            if (includeProvider) {
                Class<?> provider = Class.forName(providerName, false, loader);
                if (receiver.getDeclaredField("transfers").getType() != provider) {
                    throw new AssertionError("Receiver did not link to the supplied public PacketWeave provider");
                }
                System.out.println("PACKETWEAVE_PRESENT_OK");
            } else {
                try {
                    Class.forName(providerName, false, loader);
                    throw new AssertionError("PacketWeave leaked onto the missing-provider classpath");
                } catch (ClassNotFoundException expected) {
                    // The provider really is absent before attempting receiver linkage.
                }
                try {
                    receiver.getDeclaredFields();
                    throw new AssertionError("Receiver linked without its required public PacketWeave provider");
                } catch (NoClassDefFoundError missing) {
                    if (!missing.getMessage().contains("com/nstut/packetweave/api/TransferRegistry")) {
                        throw new AssertionError("Unexpected missing type instead of the required PacketWeave provider", missing);
                    }
                }
                System.out.println("PACKETWEAVE_MISSING_OK");
            }
        }
    }

    private static URL codeSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }
}
