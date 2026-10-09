from __future__ import annotations

import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import live_join_test


class LiveJoinHarnessTest(unittest.TestCase):
    def test_all_supported_runtime_targets_are_covered(self) -> None:
        self.assertEqual(
            {
                "fabric-1.20.1",
                "forge-1.20.1",
                "fabric-1.21.1",
                "neoforge-1.21.1",
                "neoforge-26.1.2",
            },
            set(live_join_test.TARGETS),
        )

    def test_command_is_bounded_and_non_daemon(self) -> None:
        root = Path("checkout")
        command = live_join_test.command(root, ":fabric-1.20.1:build")
        self.assertIn(Path(command[0]).name, {"gradlew", "gradlew.bat"})
        self.assertIn(":fabric-1.20.1:build", command)
        self.assertIn("--no-daemon", command)
        self.assertIn("--max-workers=4", command)
        self.assertIn("-Dorg.gradle.jvmargs=-Xmx2048m", command)

    def test_server_fixture_is_deterministic_and_offline(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            live_join_test.prepare_server(module)
            properties = (module / "run/live-join/server/server.properties").read_text(encoding="utf-8")
            self.assertIn("online-mode=false", properties)
            self.assertIn("server-port=25575", properties)
            self.assertIn("spawn-protection=0", properties)
            self.assertEqual(
                "eula=true\n",
                (module / "run/live-join/server/eula.txt").read_text(encoding="utf-8"),
            )

    def test_client_fixture_cannot_be_blocked_by_first_run_ui(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            module = Path(directory)
            live_join_test.prepare_client(module)
            options = (module / "run/live-join/client/options.txt").read_text(encoding="utf-8")
            self.assertIn("narrator:0", options)
            self.assertIn("onboardAccessibility:false", options)
            self.assertIn("skipMultiplayerWarning:true", options)
            self.assertIn("pauseOnLostFocus:false", options)

    def test_forge_test_client_disables_early_graphics_probe(self) -> None:
        import tomllib
        with tempfile.TemporaryDirectory() as directory:
            for target in live_join_test.TARGETS:
                module = Path(directory) / target
                live_join_test.prepare_client(module)
                config = module / "run/live-join/client/config/fml.toml"
                if target == "forge-1.20.1":
                    self.assertIs(False, tomllib.loads(config.read_text())["earlyWindowControl"])
                else:
                    self.assertFalse(config.exists())

    def test_pinned_native_packetweave_is_installed_on_both_sides_for_every_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for target in live_join_test.TARGETS:
                libs = root / "vendor/PacketWeave/targets" / target / "build/libs"
                libs.mkdir(parents=True)
                jar = libs / f"packetweave-{target}-0.1.1.jar"
                metadata = ("fabric.mod.json" if target.startswith("fabric-") else
                            "META-INF/mods.toml" if target.startswith("forge-") else "META-INF/neoforge.mods.toml")
                with zipfile.ZipFile(jar, "w") as archive:
                    archive.writestr(metadata, b"native metadata fixture")
                    archive.writestr("com/nstut/packetweave/api/TransferRegistry.class", b"class fixture")
                (libs / f"packetweave-{target}-0.1.1-sources.jar").write_bytes(b"not a runtime artifact")
                self.assertEqual(jar, live_join_test.packetweave_jar(root, target))
                live_join_test.install_packetweave(root / target, target, jar)
                for side in ("server", "client"):
                    installed = root / target / "run/live-join" / side / "mods" / jar.name
                    self.assertEqual(jar.read_bytes(), installed.read_bytes())

    def test_coinstallation_rejects_missing_native_jar_or_wrong_loader_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(RuntimeError, "Build the pinned"):
                live_join_test.packetweave_jar(root, "forge-1.20.1")
            jar = root / "packetweave.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("fabric.mod.json", b"{}")
                archive.writestr("com/nstut/packetweave/api/TransferRegistry.class", b"fixture")
            with self.assertRaisesRegex(RuntimeError, "native mod contents"):
                live_join_test.install_packetweave(root / "forge-1.20.1", "forge-1.20.1", jar)


if __name__ == "__main__":
    unittest.main()
