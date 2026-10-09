"""Protect the NeoForge JUnit-only native provider build and runtime isolation."""
from pathlib import Path
import re
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PacketWeaveTestProviderContractTests(unittest.TestCase):
    def test_unit_runtime_has_native_provider_instead_of_compile_only_api(self):
        build = (ROOT / "neoforge-26.1.2/build.gradle").read_text()
        self.assertIn("compileOnly project(':packetweave-api')", build)
        self.assertIn("testCompileOnly project(':packetweave-api')", build)
        self.assertIn("testRuntimeOnly files(packetWeaveTestRuntimeJar)", build)
        self.assertNotIn("testImplementation project(':packetweave-api')", build)
        self.assertNotIn("apply from: file('../packetweave.gradle')", build)
        self.assertEqual(["testRuntimeOnly"], re.findall(
            r"(?m)^\s*(\w+)\s+files\(packetWeaveTestRuntimeJar\)", build))
        self.assertIn("tasks.register('packetweaveTestRuntimeJar', Jar)", build)
        self.assertIn("from packetWeaveTestProvider.output", build)
        self.assertIn("layout.buildDirectory.dir('packetweave-test-runtime')", build)

    def test_provider_uses_real_pinned_entrypoint_metadata_and_assets(self):
        build = (ROOT / "neoforge-26.1.2/build.gradle").read_text()
        for source in ("new File(packetWeaveRoot, 'core/src/main/java')",
                       "new File(packetWeaveNativeRoot, 'src/main/java')",
                       "new File(packetWeaveNativeRoot, 'src/main/resources')",
                       "new File(packetWeaveRoot, 'icon.png')",
                       "new File(packetWeaveRoot, 'LICENSE')"):
            self.assertIn(source, build)
        self.assertIn("new File(packetWeaveNativeRoot, 'gradle.properties').withInputStream", build)
        self.assertIn("packetWeaveNativeProperties.getProperty('mod_version')", build)
        self.assertIn("expand mod_version: packetWeaveNativeVersion", build)
        self.assertIn("archiveVersion = packetWeaveNativeVersion", build)

        vendor = ROOT / "vendor/PacketWeave"
        native = vendor / "targets/neoforge-26.1.2"
        version = re.search(r"(?m)^mod_version\s*=\s*(\S+)\s*$",
                            (native / "gradle.properties").read_text()).group(1)
        metadata = (native / "src/main/resources/META-INF/neoforge.mods.toml").read_text()
        parsed = tomllib.loads(metadata.replace("${mod_version}", version))
        self.assertEqual("packetweave", parsed["mods"][0]["modId"])
        self.assertEqual(version, parsed["mods"][0]["version"])
        entrypoint = native / "src/main/java/com/nstut/packetweave/neoforge/PacketWeaveNeoForge.java"
        self.assertIn('@Mod("packetweave")', entrypoint.read_text())
        self.assertTrue((vendor / "core/src/main/java/com/nstut/packetweave/api/TransferRegistry.java").is_file())
        self.assertTrue((vendor / "icon.png").is_file())
        self.assertTrue((vendor / "LICENSE").is_file())

    def test_live_launches_have_no_implicit_unit_test_provider(self):
        runtime = (ROOT / "packetweave-runtime.gradle").read_text()
        self.assertNotIn("packetWeaveTestProvider", runtime)
        self.assertNotIn("packetWeaveTestRuntimeJar", runtime)
        build = (ROOT / "neoforge-26.1.2/build.gradle").read_text()
        self.assertNotRegex(build, r"sourceSet\(packetWeaveTestProvider\)")
        self.assertNotRegex(build, r"(?s)jar\s*\{[^}]*packetWeaveTestProvider")


if __name__ == "__main__":
    unittest.main()
