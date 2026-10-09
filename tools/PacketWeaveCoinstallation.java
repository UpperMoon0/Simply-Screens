import cpw.mods.cl.JarModuleFinder;
import cpw.mods.jarhandling.SecureJar;

import java.lang.module.ModuleFinder;
import java.lang.module.ResolutionException;
import java.nio.file.Path;
import java.util.Set;

/** Exercises the same SecureJarHandler resolveAndBind path as ModLauncher. */
public final class PacketWeaveCoinstallation {
    private static final String PUBLIC_API = "com.nstut.packetweave.api";
    private static final String PRIVATE_API = "com.nstut.simplyscreens.internal.packetweave.api";

    private static void resolve(Path first, Path second, boolean duplicateControl) throws Exception {
        SecureJar library = SecureJar.from(first);
        SecureJar screens = SecureJar.from(second);
        try {
            var finder = JarModuleFinder.of(library, screens);
            var libraryModule = finder.find(library.name()).orElseThrow().descriptor();
            var screensModule = finder.find(screens.name()).orElseThrow().descriptor();
            if (library.name().equals(screens.name()) || finder.findAll().size() != 2) {
                throw new AssertionError("The co-installation check must resolve two distinct modules");
            }
            if (!libraryModule.packages().contains(PUBLIC_API)
                    || !screensModule.packages().contains(duplicateControl ? PUBLIC_API : PRIVATE_API)) {
                throw new AssertionError("Expected the actual PacketWeave core packages in both jars");
            }
            if (!duplicateControl && screensModule.packages().contains(PUBLIC_API)) {
                throw new AssertionError("Simply Screens still exports the standalone PacketWeave package");
            }
            try {
                var resolved = ModuleLayer.boot().configuration().resolveAndBind(
                        finder, ModuleFinder.of(), Set.of(library.name(), screens.name()));
                if (duplicateControl) {
                    throw new AssertionError("Negative control unexpectedly accepted overlapping PacketWeave packages");
                }
                if (resolved.findModule(library.name()).isEmpty() || resolved.findModule(screens.name()).isEmpty()) {
                    throw new AssertionError("Both co-installed modules must be resolved");
                }
                System.out.println("PASS: SecureJarHandler resolved " + library.name() + " with " + screens.name());
            } catch (ResolutionException expected) {
                if (!duplicateControl || !expected.getMessage().contains(PUBLIC_API)) {
                    throw expected;
                }
                System.out.println("PASS: duplicate-core negative control rejected: " + expected.getMessage());
            }
        } finally {
            screens.close();
            library.close();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected standalone jar, Screens jar, and two original-core control jars");
        }
        resolve(Path.of(args[2]), Path.of(args[3]), true);
        resolve(Path.of(args[0]), Path.of(args[1]), false);
    }
}
