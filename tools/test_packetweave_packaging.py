import json
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
    def test_compiled_consumer_uses_only_the_standalone_api(self):
        # Compile the real pinned API and a consumer with bytecode references to
        # it. No PacketWeave class is bundled into the consumer fixture.
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            original = ROOT / "vendor/PacketWeave/core/src/main/java"
            license_file = ROOT / "vendor/PacketWeave/LICENSE"
            classes = work / "packetweave-classes"
            compile_java(sorted(original.rglob("*.java")), classes)
            standalone = work / "packetweave.jar"
            core_jar(classes, standalone, "packetweave", license_file)
            source = work / "Consumer.java"
            source.write_text("package com.nstut.simplyscreens; public class Consumer { "
                              "public static Object limits() { return com.nstut.packetweave.api.TransferLimits.defaults(); } }")
            consumer = work / "consumer"
            compile_java([source], consumer, str(standalone))
            screens = work / "simply_screens.jar"
            core_jar(consumer, screens, "simply_screens", license_file)
            with zipfile.ZipFile(screens, "a") as archive:
                archive.writestr("fabric.mod.json", json.dumps({"id": "simply_screens", "depends": {"packetweave": ">=0.1.5"}}))
            verify(screens, standalone, Path(os.environ["PACKETWEAVE_TEST_DEPENDENCY_CACHE"]))


if __name__ == "__main__":
    unittest.main()
