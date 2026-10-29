"""Keep evergreen user/developer guidance separate from versioned release records."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
EVERGREEN_DOCS = (
    "README.md",
    "TESTING.md",
    "curseforge.md",
    "docs/ci-optimization.md",
    "docs/network-access.md",
    "docs/playlist-design.md",
    "docs/requested-generations.md",
    "docs/screen-api.md",
    "forge-1.12.2/README.md",
    "neoforge-26.1.2/README.md",
)


class EvergreenDocsTest(unittest.TestCase):
    def test_evergreen_guides_do_not_pin_mod_releases(self):
        # Minecraft targets and JDK/compiler versions are compatibility identifiers;
        # historical/release-specific dependency pins belong in changelog or lockfiles.
        release_pin = re.compile(
            r"(?:(?:PacketWeave|OpenUI(?: MC)?|Screens|Sable)\\s+v?\\d+\\.\\d+(?:\\.\\d+)?\\b"
            r"|\\bv?0\\.(?:8|9|10)\\.\\d+\\b)"
        )
        for name in EVERGREEN_DOCS:
            with self.subTest(document=name):
                contents = (ROOT / name).read_text(encoding="utf-8")
                self.assertIsNone(release_pin.search(contents), "Use release metadata, not hard-coded version pins")

    def test_release_specific_metadata_stays_in_authoritative_files(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        changelog = (ROOT / "changelog/0.10.2.md").read_text(encoding="utf-8")
        native = (ROOT / "docs/requested-generations.md").read_text(encoding="utf-8")
        self.assertIn("receiver receipts", readme)
        self.assertNotIn("Packets remain wire-compatible", readme)
        self.assertIn("loader metadata", readme)
        self.assertIn("tools/requested-dependencies.lock.json", native)
        self.assertIn("PacketWeave 0.1.5", changelog)
        self.assertIn("two-second retry cooldown", changelog)
        self.assertIn("invalidation and disconnect", changelog)


if __name__ == "__main__":
    unittest.main()
