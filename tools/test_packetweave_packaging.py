import subprocess
import tempfile
import unittest
from pathlib import Path
from visual_test import snapshot


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


if __name__ == "__main__":
    unittest.main()
