import tempfile
import unittest
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

    def test_expected_artifacts_cover_every_supported_target(self):
        paths = build_openui.artifact_paths("0.0.8", Path("/m2"))
        self.assertEqual(len(build_openui.TARGETS), len(paths))
        for target, path in zip(build_openui.TARGETS, paths):
            self.assertEqual(f"openui-mc-{target}-0.0.8.jar", path.name)
            self.assertIn(f"openui-mc-{target}", path.parts)


if __name__ == "__main__":
    unittest.main()
