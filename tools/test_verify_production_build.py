from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from verify_production_build import retryable_build_failure, verify_production_build


TRANSIENT = "Could not resolve all files: received status code 503 from server"


class ProductionBuildRetryTests(unittest.TestCase):
    def test_requires_both_dependency_context_and_transient_evidence(self):
        for text in (TRANSIENT, "Could not download artifact.jar: connection reset",
                     "downloadAssets failed: read timed out"):
            self.assertTrue(retryable_build_failure(text))
        for text in ("Could not resolve artifact: HTTP404", "connection timed out", "build failed",
                     "Could not download artifact.jar: checksum mismatch",
                     TRANSIENT + "\n* What went wrong:\nUnexpected NullPointerException in task setup"):
            self.assertFalse(retryable_build_failure(text))

    def test_test_compile_metadata_and_assertion_failures_override_network_text(self):
        failures = (
            "> Task :neoforge-26.1.2:test FAILED", "There were failing tests",
            "AssertionError: expected true", "assertion failed",
            "Compilation failed; see compiler output", "Foo.java:19: error: incompatible types",
            "> Task :fabric-1.21.1:processResources FAILED",
            "> Task :neoforge-26.1.2:generateModMetadata FAILED",
            "Invalid mod metadata", "Invalid Simply Screens production jar",
            "ModLoadingException: missing PacketWeave",
        )
        for failure in failures:
            with self.subTest(failure=failure):
                self.assertFalse(retryable_build_failure(TRANSIENT + "\n" + failure))

    def test_never_retries_a_deterministic_failure_into_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch("verify_production_build.snapshot"), patch("verify_production_build.scope_gradle"), \
                 patch("verify_production_build.run_attempt", side_effect=[(1, "> Task :neoforge-26.1.2:test FAILED"), (0, "success")]) as attempt, \
                 patch("verify_production_build.subprocess.run") as packaging, patch("verify_production_build.time.sleep") as sleep:
                with self.assertRaises(subprocess.CalledProcessError):
                    verify_production_build(Path(directory), "neoforge-26.1.2")
                self.assertEqual(1, attempt.call_count)
                packaging.assert_not_called()
                sleep.assert_not_called()

    def test_retries_a_confirmed_transient_download_then_checks_packaging(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch("verify_production_build.snapshot"), patch("verify_production_build.scope_gradle"), \
                 patch("verify_production_build.run_attempt", side_effect=[(1, TRANSIENT), (0, "success")]) as attempt, \
                 patch("verify_production_build.subprocess.run") as packaging, patch("verify_production_build.time.sleep") as sleep:
                verify_production_build(Path(directory), "fabric-1.21.1")
                self.assertEqual(2, attempt.call_count)
                packaging.assert_called_once()
                sleep.assert_called_once_with(5)


if __name__ == "__main__":
    unittest.main()
