"""Run the actual test agent against tiny synthetic launchers, without Minecraft."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

import playlist_test as harness
from assert_production_jar import obsolete_entries


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required for agent bytecode tests")
class PlaylistLauncherAgentTests(unittest.TestCase):
    def exercise(self, *, version="5.2.91", factory="newFixedThreadPool(1)", leak=False):
        with tempfile.TemporaryDirectory() as tmp:
            stage = Path(tmp)
            source = stage / "tools/playlist/DevLauncherAgent.java"
            source.parent.mkdir(parents=True)
            shutil.copyfile(harness.ROOT / "tools/playlist/DevLauncherAgent.java", source)
            agent = harness.build_launcher_agent(stage)
            self.assertIn("com/nstut/simplyscreens/testing/playlist/DevLauncherAgent.class", obsolete_entries(agent))
            nested = stage / "nested-production.jar"
            with zipfile.ZipFile(nested, "w") as archive:
                archive.write(agent, "META-INF/jars/test-agent.jar")
            self.assertIn("META-INF/jars/test-agent.jar!com/nstut/simplyscreens/testing/playlist/DevLauncherAgent.class",
                          obsolete_entries(nested))
            sources = []
            owners = [line.split(" ", 2)[2] for line in harness.LAUNCHER_PATCHES]
            for owner in owners + ["example/UnrelatedGamePool"]:
                package, name = owner.rsplit("/", 1)
                path = stage / "src" / (owner + ".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"""package {package.replace('/', '.')};
import java.util.concurrent.*;
public class {name} {{
  public static boolean run(boolean leaveOpen) throws Exception {{
    // Exercise long/double constants, lambdas and modified UTF-8 in the parser.
    long wide = 123456789012345L; double decimal = 1.23456789;
    String text = "λ null \\u0000 ";
    ExecutorService pool = Executors.{factory};
    boolean daemon = pool.submit(() -> Thread.currentThread().isDaemon()).get();
    if (!leaveOpen) pool.shutdown();
    return daemon;
  }}
}}
""", encoding="utf-8")
                sources.append(path)
            main = stage / "src/Main.java"
            main.write_text("""public class Main {
  public static void main(String[] args) throws Exception {
    boolean leaveOpen = Boolean.parseBoolean(args[0]);
    boolean expected = Boolean.parseBoolean(args[1]);
""" + "\n".join(f"    if ({owner.replace('/', '.')}.run(leaveOpen) != expected) throw new AssertionError(\"scoped pool\");" for owner in owners)
                            + """
    if (example.UnrelatedGamePool.run(false)) throw new AssertionError("game pool was changed");
    System.out.println("scoped launcher pools verified");
  }
}
""", encoding="utf-8")
            sources.append(main)
            classes = stage / "fixture-classes"; classes.mkdir()
            subprocess.run([shutil.which("javac"), "--release", "17", "-encoding", "UTF-8", "-d", str(classes),
                            *map(str, sources)], check=True, capture_output=True, text=True)
            jar = stage / "fixture-runtime.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\nImplementation-Version: {version}\n\n")
                for path in classes.rglob("*.class"):
                    archive.write(path, path.relative_to(classes).as_posix())
            expected = version == "5.2.91" and factory == "newFixedThreadPool(1)"
            result = subprocess.run([shutil.which("java"), f"-javaagent:{agent}", "-cp", str(jar),
                                     "Main", str(leak).lower(), str(expected).lower()],
                                    env=os.environ | {"SS_PLAYLIST_DIR": str(stage)},
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("scoped launcher pools verified", result.stdout)
            receipt = next(stage.glob("launcher-agent-*.txt"))
            pid = int(receipt.stem.removeprefix("launcher-agent-"))
            if expected:
                self.assertEqual(sorted(harness.LAUNCHER_PATCHES),
                                 harness.validate_launcher_patches(stage, "fabric-1.21.1", pid))
                for bad in ("", receipt.read_text() + "unexpected\n", receipt.read_text() * 2):
                    receipt.write_text(bad)
                    with self.assertRaises(ValueError):
                        harness.validate_launcher_patches(stage, "fabric-1.21.1", pid)
            else:
                self.assertTrue(all(line.startswith("FAIL ") for line in receipt.read_text().splitlines()))
                with self.assertRaises(ValueError):
                    harness.validate_launcher_patches(stage, "fabric-1.21.1", pid)

    def test_only_two_exact_pools_become_daemon_and_unclosed_pools_exit_naturally(self):
        self.exercise(leak=True)

    def test_changed_runtime_version_fails_closed(self):
        self.exercise(version="5.2.92")

    def test_changed_method_layout_fails_closed(self):
        self.exercise(factory="newCachedThreadPool()")

    def test_no_patch_receipt_fails_closed_but_moddev_needs_no_patch(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual([], harness.validate_launcher_patches(Path(tmp), "neoforge-26.1.2", 123))
            with self.assertRaises(FileNotFoundError):
                harness.validate_launcher_patches(Path(tmp), "fabric-1.20.1", 123)


if __name__ == "__main__":
    unittest.main()
