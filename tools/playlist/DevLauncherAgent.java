package com.nstut.simplyscreens.testing.playlist;

import java.io.*;
import java.lang.instrument.*;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;

/**
 * Test-only repair for Architectury Runtime 5.2.91's development-launcher pools.
 * Hosted thread dumps prove both pools outlive a fully stopped Minecraft server.
 * Upstream transformer commit 2698278e63d0afea4969b5fd6c96a68fb64432f2:
 * https://github.com/architectury/architectury-transformer/blob/2698278e63d0afea4969b5fd6c96a68fb64432f2/src/main/java/dev/architectury/transformer/transformers/classpath/ReadClasspathProviderImpl.java
 * provide() awaits termination without shutdown. TransformerRuntime retains the
 * TinyRemapperPreparedTransformerHandler without closing its remapper:
 * https://github.com/architectury/architectury-transformer/blob/2698278e63d0afea4969b5fd6c96a68fb64432f2/src/runtime/java/dev/architectury/transformer/TransformerRuntime.java
 * The remapper's fixed pool is documented at upstream commit
 * https://github.com/architectury/tiny-remapper/blob/1b6dc8426ec438516cc6d43398409d8e29895290/src/main/java/net/fabricmc/tinyremapper/TinyRemapper.java
 *
 * Only the exact two shaded runtime classes, version, and factory descriptor are
 * accepted. No game/mod executors are changed. Failed/missing patch receipts fail
 * the Python gate; a stuck or killed server can never certify a restart.
 */
public final class DevLauncherAgent implements ClassFileTransformer {
    private static final String VERSION = "5.2.91";
    private static final Set<String> OWNERS = Set.of(
            "dev/architectury/transformer/transformers/classpath/ReadClasspathProviderImpl",
            "dev/architectury/transformer/shadowed/impl/dev/architectury/tinyremapper/TinyRemapper");
    private static final String HELPER = DevLauncherAgent.class.getName().replace('.', '/');
    private static final AtomicInteger THREAD = new AtomicInteger();
    private static Path receipt;

    public static void premain(String arguments, Instrumentation instrumentation) {
        String directory = System.getenv("SS_PLAYLIST_DIR");
        if (directory == null) throw new IllegalStateException("Playlist launcher agent requires test evidence directory");
        receipt = Path.of(directory, "launcher-agent-" + ProcessHandle.current().pid() + ".txt");
        instrumentation.addTransformer(new DevLauncherAgent());
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                            ProtectionDomain domain, byte[] source) {
        if (!OWNERS.contains(name)) return null;
        try {
            if (domain == null || domain.getCodeSource() == null) throw new IOException("Missing runtime code source");
            Path jar = Path.of(domain.getCodeSource().getLocation().toURI());
            try (JarFile file = new JarFile(jar.toFile())) {
                if (file.getManifest() == null || !VERSION.equals(file.getManifest().getMainAttributes().getValue("Implementation-Version")))
                    throw new IOException("Unexpected Architectury runtime version: " + jar);
            }
            byte[] changed = patch(source);
            record("PATCH " + VERSION + " " + name);
            return changed;
        } catch (Exception failure) {
            record("FAIL " + name + " " + failure);
            return null;
        }
    }

    /** Same pool semantics, except dev-only idle workers cannot keep the JVM alive. */
    public static ExecutorService newFixedThreadPool(int count) {
        return Executors.newFixedThreadPool(count, runnable -> {
            Thread thread = new Thread(runnable, "Playlist test Architectury worker " + THREAD.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private static synchronized void record(String line) {
        try { Files.writeString(receipt, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException failure) { throw new IllegalStateException("Cannot record development-launcher patch", failure); }
    }

    /** Redirect one exact Methodref, preserving every other constant and bytecode. */
    static byte[] patch(byte[] source) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(source));
        if (input.readInt() != 0xcafebabe) throw new IOException("Invalid class header");
        input.readUnsignedShort(); input.readUnsignedShort();
        int count = input.readUnsignedShort();
        if (count > 65533) throw new IOException("Full constant pool");
        int[] tags = new int[count], offsets = new int[count], first = new int[count], second = new int[count];
        String[] strings = new String[count];
        for (int i = 1; i < count; i++) {
            offsets[i] = source.length - input.available();
            tags[i] = input.readUnsignedByte();
            switch (tags[i]) {
                case 1 -> strings[i] = input.readUTF();
                case 3, 4 -> input.skipNBytes(4);
                case 5, 6 -> { input.skipNBytes(8); i++; }
                case 7, 8, 16, 19, 20 -> first[i] = input.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> { first[i] = input.readUnsignedShort(); second[i] = input.readUnsignedShort(); }
                case 15 -> input.skipNBytes(3);
                default -> throw new IOException("Unknown constant tag " + tags[i]);
            }
        }
        int end = source.length - input.available(), matches = 0;
        byte[] changed = source.clone();
        for (int i = 1; i < count; i++) {
            if (tags[i] == 10 && "java/util/concurrent/Executors".equals(strings[first[first[i]]])
                    && "newFixedThreadPool".equals(strings[first[second[i]]])
                    && "(I)Ljava/util/concurrent/ExecutorService;".equals(strings[second[second[i]]])) {
                changed[offsets[i] + 1] = (byte) ((count + 1) >>> 8);
                changed[offsets[i] + 2] = (byte) (count + 1);
                matches++;
            }
        }
        if (matches != 1) throw new IOException("Expected exactly one fixed-pool Methodref, found " + matches);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(source.length + HELPER.length() + 6);
        DataOutputStream output = new DataOutputStream(bytes);
        output.write(changed, 0, 8); output.writeShort(count + 2);
        output.write(changed, 10, end - 10);
        output.writeByte(1); output.writeUTF(HELPER);
        output.writeByte(7); output.writeShort(count);
        output.write(changed, end, changed.length - end);
        return bytes.toByteArray();
    }
}
