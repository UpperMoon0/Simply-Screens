from __future__ import annotations

import sys
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
