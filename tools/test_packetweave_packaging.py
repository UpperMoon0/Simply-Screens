import os
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

from visual_test import snapshot
from verify_packetweave_coinstallation import ROOT, compile_java, core_jar, dependency_classpath, verify


class PacketWeaveSnapshotTests(unittest.TestCase):
    def test_snapshot_copies_pinned_submodule_sources_and_license_but_not_build_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            library, root = base / "library", base / "screens"
            for repo in (library, root):
                repo.mkdir()
                self.git(repo, "init")
                self.git(repo, "config", "user.name", "Fixture")
                self.git(repo, "config", "user.email", "fixture@example.invalid")
            (library / "core/src/main/java").mkdir(parents=True)
            (library / "core/src/main/java/Transfer.java").write_text("class Transfer {}")
            (library / "LICENSE").write_text("MIT test fixture")
            (library / ".gitignore").write_text("build/\n")
            self.git(library, "add", ".")
            self.git(library, "commit", "-m", "fixture")
            self.git(root, "-c", "protocol.file.allow=always", "submodule", "add", str(library), "vendor/PacketWeave")
            module = root / "vendor/PacketWeave"
            (module / "build").mkdir()
            (module / "build/private-cache").write_text("must not copy")
            destination = base / "snapshot"
            first = snapshot(root, destination)
            self.assertEqual("class Transfer {}", (destination / "vendor/PacketWeave/core/src/main/java/Transfer.java").read_text())
            self.assertEqual("MIT test fixture", (destination / "vendor/PacketWeave/LICENSE").read_text())
            self.assertFalse((destination / "vendor/PacketWeave/build").exists())
            self.assertFalse((destination / "vendor/PacketWeave/.git").exists())
            (module / "core/src/main/java/Transfer.java").write_text("class Changed {}")
            self.assertNotEqual(first, snapshot(root, base / "changed"))

    @staticmethod
    def git(root, *arguments):
        subprocess.run(["git", *arguments], cwd=root, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


class PacketWeaveModuleTests(unittest.TestCase):
    def test_module_fixture_packs_compiled_classes_with_distinct_module_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            classes = work / "classes"
            (classes / "com/nstut/packetweave/api").mkdir(parents=True)
            (classes / "com/nstut/packetweave/api/TransferId.class").write_bytes(b"fixture")
            (classes / "do-not-package.txt").write_text("not bytecode")
            license_file = work / "LICENSE"
            license_file.write_text("MIT license fixture")
            jar = work / "packetweave.jar"
            core_jar(classes, jar, "packetweave", license_file)
            with zipfile.ZipFile(jar) as archive:
                self.assertIn(b"Automatic-Module-Name: packetweave\r\n", archive.read("META-INF/MANIFEST.MF"))
                self.assertEqual(b"MIT license fixture", archive.read("META-INF/LICENSE-PacketWeave"))
                self.assertNotIn("do-not-package.txt", archive.namelist())
                self.assertIn("com/nstut/packetweave/api/TransferId.class", archive.namelist())

    def test_dependency_cache_rejects_corrupt_or_replaced_jars(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            (cache / "securejarhandler-3.0.8.jar").write_bytes(b"wrong bytes")
            with self.assertRaisesRegex(RuntimeError, "Checksum mismatch"):
                dependency_classpath(cache)

    @unittest.skipUnless(os.environ.get("PACKETWEAVE_TEST_DEPENDENCY_CACHE"),
                         "set PACKETWEAVE_TEST_DEPENDENCY_CACHE to run the real SecureJarHandler compiled-core regression")
    def test_compiled_pinned_core_coinstalls_only_when_relocated(self):
        # Real bytecode from the pinned core, not hand-written class stubs. The
        # live-join job additionally applies this check to actual production jars.
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            original = ROOT / "vendor/PacketWeave/core/src/main/java"
            relocated = work / "relocated-source"
            for source in original.rglob("*.java"):
                path = relocated / source.relative_to(original).as_posix().replace(
                    "com/nstut/packetweave", "com/nstut/simplyscreens/internal/packetweave")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source.read_text(encoding="utf-8").replace(
                    "com.nstut.packetweave", "com.nstut.simplyscreens.internal.packetweave"), encoding="utf-8")
            license_file = ROOT / "vendor/PacketWeave/LICENSE"
            for name, sources in (("packetweave", original), ("simply_screens", relocated)):
                classes = work / (name + "-classes")
                compile_java(sorted(sources.rglob("*.java")), classes)
                core_jar(classes, work / (name + ".jar"), name, license_file)
            verify(work / "simply_screens.jar", work / "packetweave.jar",
                   Path(os.environ["PACKETWEAVE_TEST_DEPENDENCY_CACHE"]))


if __name__ == "__main__":
    unittest.main()
