import tempfile
import unittest
import subprocess
from unittest.mock import patch
from pathlib import Path

import build_openui


class BuildOpenUiTest(unittest.TestCase):
    def test_property_value_accepts_spaced_gradle_property(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gradle.properties"
            path.write_text("openui_version = 0.0.8\n", encoding="utf-8")
            self.assertEqual("0.0.8", build_openui.property_value(path, "openui_version"))

    def test_transient_repository_failures_are_retryable(self):
        for status in (429, 500, 502, 503, 504):
            self.assertTrue(build_openui.transient_failure(
                f"Could not GET artifact. Received status code {status} from server"
            ))
        self.assertFalse(build_openui.transient_failure("Compilation failed with 12 errors"))

    def test_each_target_has_an_isolated_serial_gradle_invocation(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / "gradlew").touch()
            (project / "gradlew.bat").touch()
            for target in build_openui.TARGETS:
                command = build_openui.gradle_command(project, target)
                self.assertEqual([f":{target}:publishToMavenLocal"], [arg for arg in command if arg.startswith(":")])
                self.assertIn("--no-daemon", command)
                self.assertIn("--no-parallel", command)
                self.assertIn("--max-workers=1", command)
            with self.assertRaises(ValueError):
                build_openui.gradle_command(project, "unknown")

    @patch.object(build_openui, "required_version", return_value="0.0.8")
    @patch.object(build_openui, "property_value", return_value="0.0.8")
    @patch.object(build_openui, "gradle_command", side_effect=lambda project, target: [target])
    @patch.object(build_openui, "time")
    def test_transient_retry_is_scoped_to_failed_target(self, time, command, actual, required):
        outcomes = [(0, ""), (1, "connection reset"), (0, ""), (0, ""), (0, ""), (0, "")]
        with patch.object(build_openui, "run_attempt", side_effect=outcomes) as run:
            build_openui.build(Path("source"), attempts=3, retry_delay=1)
        expected = list(build_openui.TARGETS)
        expected.insert(1, expected[1])
        self.assertEqual(expected, [call.args[0][0] for call in run.call_args_list])
        time.sleep.assert_called_once_with(1)

    @patch.object(build_openui, "required_version", return_value="0.0.8")
    @patch.object(build_openui, "property_value", return_value="0.0.8")
    @patch.object(build_openui, "gradle_command", side_effect=lambda project, target: [target])
    def test_mapping_or_compile_failure_stops_without_retry_or_later_targets(self, command, actual, required):
        for failure in ("NoSuchFileException: mappings-srg.tiny", "Compilation failed",
                        "Received status code 503; retry succeeded\nNoSuchFileException: mappings-srg.tiny",
                        "connection reset; retry succeeded\nCompilation failed"):
            with self.subTest(failure=failure), patch.object(build_openui, "run_attempt", return_value=(1, failure)) as run:
                with self.assertRaises(subprocess.CalledProcessError):
                    build_openui.build(Path("source"), attempts=3, retry_delay=0)
                self.assertEqual(1, run.call_count)

    def test_cold_ci_exercises_real_build_without_production_cache_changes(self):
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/cold-openui.yml").read_text()
        self.assertIn("timeout-minutes: 30", workflow)
        self.assertIn("        env:\n          GRADLE_USER_HOME: ${{ runner.temp }}/cold-openui-gradle", workflow)
        self.assertIn('test ! -e "$GRADLE_USER_HOME"', workflow)
        self.assertIn('test ! -d "$HOME/.m2/repository/com/nstut"', workflow)
        self.assertIn("python3 tools/build_openui.py --attempts 1", workflow)
        self.assertIn("python3 tools/build_openui.py --verify-only", workflow)
        self.assertNotIn("actions/cache@", workflow)
        self.assertNotIn("cache: gradle", workflow)

    def test_expected_artifacts_cover_every_supported_target(self):
        paths = build_openui.artifact_paths("0.0.8", Path("/m2"))
        self.assertEqual(len(build_openui.TARGETS), len(paths))
        for target, path in zip(build_openui.TARGETS, paths):
            self.assertEqual(f"openui-mc-{target}-0.0.8.jar", path.name)
            self.assertIn(f"openui-mc-{target}", path.parts)


if __name__ == "__main__":
    unittest.main()
