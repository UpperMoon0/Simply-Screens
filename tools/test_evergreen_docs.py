"""Keep evergreen guidance separate from release pins and historical evidence."""

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
    "docs/patchouli.md",
    "docs/playlist-design.md",
    "docs/requested-generations.md",
    "docs/screen-api.md",
    "docs/settings.md",
    "forge-1.12.2/README.md",
    "neoforge-26.1.2/README.md",
)

# Minecraft target names and Java major levels are compatibility identifiers.
# Source commits, native releases, loader/Gradle patch builds and external tags
# belong in build metadata, versioned changelogs or explicit historical records.
MOD_RELEASE_PIN = re.compile(
    r"\b(?:PacketWeave|OpenUI(?: MC)?|Screens|Sable|Companion)\s+v?\d+\.\d+(?:\.\d+){0,2}\b"
    r"|\bv?0\.(?:8|9|10)\.\d+\b"
)
BUILD_DISTRIBUTION_PIN = re.compile(
    r"\bGradle\s+\d+\.\d+(?:\.\d+)?\b"
    r"|\b(?:NeoForge|Forge)\s+\d+(?:\.\d+){3}\b"
)
EXTERNAL_RELEASE_TAG = re.compile(r"\bmc\d+\.\d+\.\d+-\d+\.\d+\.\d+[-\w.]*\b")


class EvergreenDocsTest(unittest.TestCase):
    def test_evergreen_guides_do_not_pin_mod_or_build_releases(self):
        for name in EVERGREEN_DOCS:
            with self.subTest(document=name):
                contents = (ROOT / name).read_text(encoding="utf-8")
                for pattern in (MOD_RELEASE_PIN, BUILD_DISTRIBUTION_PIN, EXTERNAL_RELEASE_TAG):
                    self.assertIsNone(
                        pattern.search(contents),
                        f"Move pins from evergreen {name} to authoritative metadata or history",
                    )

    def test_pin_detector_rejects_examples_but_allows_target_names(self):
        for sample, pattern in (
            ("Sable 2.0.6", MOD_RELEASE_PIN),
            ("Companion 1.6.0", MOD_RELEASE_PIN),
            ("Gradle 8.14", BUILD_DISTRIBUTION_PIN),
            ("Forge 14.23.5.2864", BUILD_DISTRIBUTION_PIN),
            ("NeoForge 26.2.0.93", BUILD_DISTRIBUTION_PIN),
            ("mc1.21.1-2.0.6-neoforge", EXTERNAL_RELEASE_TAG),
        ):
            with self.subTest(sample=sample):
                self.assertIsNotNone(pattern.search(sample))
        for target in ("NeoForge 26.1.2", "Minecraft 1.21.1", "Java 25"):
            with self.subTest(target=target):
                self.assertIsNone(BUILD_DISTRIBUTION_PIN.search(target))

    def test_native_readme_is_project_specific(self):
        doc = (ROOT / "neoforge-26.1.2/README.md").read_text(encoding="utf-8")
        self.assertIn("# Simply Screens", doc)
        self.assertIn("../README.md", doc)
        self.assertIn("../TESTING.md", doc)
        self.assertNotIn("This template repository", doc)
        self.assertNotIn("clone this template", doc.lower())

    def test_release_specific_metadata_stays_in_authoritative_files(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        changelog = (ROOT / "changelog/0.10.2.md").read_text(encoding="utf-8")
        native = (ROOT / "docs/requested-generations.md").read_text(encoding="utf-8")
        history = (ROOT / "docs/native-acceptance-history.md").read_text(encoding="utf-8")
        self.assertIn("receiver receipts", readme)
        self.assertNotIn("Packets remain wire-compatible", readme)
        self.assertIn("loader metadata", readme)
        self.assertIn("tools/requested-dependencies.lock.json", native)
        self.assertIn("PacketWeave 0.1.5", changelog)
        self.assertIn("two-second retry cooldown", changelog)
        self.assertIn("invalidation and disconnect", changelog)
        self.assertIn("## Sable compatibility checkpoint", history)
        self.assertIn("227077f7f528c9beab1336a9fad2e27b754e4e70", history)


if __name__ == "__main__":
    unittest.main()
