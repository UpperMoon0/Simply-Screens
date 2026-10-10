import json
from pathlib import Path
import tempfile
import sys
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import zipfile

from assert_production_jar import loader_metadata
from packetweave_runtime import assert_dependency_rejected, check_dependency_rejections, incompatible_jar, native_version, wait_for_dependency_rejection


class PacketWeaveRuntimeTests(unittest.TestCase):
    def test_real_native_negative_fixture_changes_only_loader_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for target in ("fabric-1.20.1", "forge-1.20.1", "neoforge-1.21.1", "neoforge-26.1.2"):
                source, invalid = root / "source.jar", root / "invalid.jar"
                path = loader_metadata(target)
                text = (json.dumps({"id": "packetweave", "version": "0.1.1"}) if target.startswith("fabric-") else
                        '[[mods]]\nmodId="packetweave"\nversion="0.1.1"\n')
                with zipfile.ZipFile(source, "w") as archive:
                    archive.writestr(path, text)
                    archive.writestr("com/nstut/packetweave/api/TransferRegistry.class", b"unchanged native bytecode")
                incompatible_jar(source, invalid, target)
                self.assertEqual("0.1.1", native_version(source, target))
                self.assertEqual("0.1.0", native_version(invalid, target))
                with zipfile.ZipFile(invalid) as archive:
                    self.assertEqual(b"unchanged native bytecode", archive.read("com/nstut/packetweave/api/TransferRegistry.class"))

    def test_native_missing_and_incompatible_diagnostics_are_required(self):
        for mode, actual in (("missing", "[MISSING]"), ("incompatible", "0.1.0")):
            forge = ("Missing or unsupported mandatory dependencies:\n"
                     "Mod ID: 'packetweave', Requested by: 'simply_screens', "
                     f"Expected range: '[0.1.1,)', Actual version: '{actual}'")
            fabric = ("Mod 'Simply Screens' (simply_screens) requires version 0.1.1 or later of "
                      "mod 'PacketWeave' (packetweave), " + ("which is missing!" if mode == "missing" else "but only version 0.1.0 is present!"))
            neo = ("Mod §esimply_screens§r requires §epacketweave§r 0.1.1 or above\n"
                   "Currently, packetweave is " + ("not installed" if mode == "missing" else "0.1.0"))
            forge += "\nFailed to start the minecraft server"
            neo += "\nFailed to start the minecraft server"
            fabric += "\nIncompatible mods found!\nnet.fabricmc.loader.impl.FormattedException"
            for output in (forge, fabric, neo):
                assert_dependency_rejected(output, 1, mode)
                with self.assertRaises(RuntimeError):
                    assert_dependency_rejected(output, 1, "incompatible" if mode == "missing" else "missing")
                with self.assertRaises(RuntimeError):
                    assert_dependency_rejected(output, 0, mode)
                with self.assertRaises(RuntimeError):
                    assert_dependency_rejected(output + "\nDone (1.2s)!", 1, mode)

    def test_negative_launches_remove_then_substitute_and_restore_the_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = "forge-1.20.1"
            mods = root / target / "run/live-join/server/mods"
            mods.mkdir(parents=True)
            native = root / "packetweave-forge-1.20.1-0.1.1.jar"
            with zipfile.ZipFile(native, "w") as archive:
                archive.writestr(loader_metadata(target), '[[mods]]\nmodId="packetweave"\nversion="0.1.1"\n')
                archive.writestr("com/nstut/packetweave/api/TransferRegistry.class", b"unchanged")
            installed = mods / native.name
            installed.write_bytes(native.read_bytes())
            launches = []
            def launch(command, cwd):
                jars = list(mods.glob("*.jar"))
                mode = "missing" if not launches else "incompatible"
                self.assertEqual(0 if mode == "missing" else 1, len(jars))
                if jars:
                    self.assertEqual("0.1.0", native_version(jars[0], target))
                launches.append(mode)
                actual = "[MISSING]" if mode == "missing" else "0.1.0"
                output = ("Missing or unsupported mandatory dependencies:\nMod ID: 'packetweave', "
                          "Requested by: 'simply_screens', Expected range: '[0.1.1,)', Actual version: '" + actual + "'")
                return SimpleNamespace(returncode=1, communicate=lambda **kwargs: (output, None))
            def observe(process, mode, timeout, log, prefix):
                output, _ = process.communicate()
                log.write_text(output)
                assert_dependency_rejected(output + "\nFailed to start the minecraft server", process.returncode, mode)
                return False
            with patch("live_join_test.popen", side_effect=launch), patch("packetweave_runtime.wait_for_dependency_rejection", side_effect=observe):
                check_dependency_rejections(root, target, native, 10)
            self.assertEqual(["missing", "incompatible"], launches)
            self.assertEqual([installed], list(mods.glob("*.jar")))
            self.assertEqual(native.read_bytes(), installed.read_bytes())
            self.assertTrue((mods.parent.parent / "dependency-checks/missing-server.log").is_file())
            self.assertTrue((mods.parent.parent / "dependency-checks/incompatible-server.log").is_file())

    def run_streaming_fixture(self, output, mode="missing", linger=False, should_pass=False, startup_delay=0):
        import live_join_test as live
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "dependency.log"
            ready = root / "fixture-ready"
            script = ("import sys,time; from pathlib import Path; "
                      + f"time.sleep({startup_delay!r}); "
                      + "print(" + repr(output) + ", flush=True); "
                      + "Path(" + repr(str(ready)) + ").write_text('ready'); "
                      + ("time.sleep(30); " if linger else "") + "sys.exit(1)")
            process = live.popen([sys.executable, "-u", "-c", script], root)
            try:
                # Interpreter startup is not part of the short observer budget.
                # The child acknowledges readiness only after flushing all test
                # output, so even a slow first spawn cannot lose log assertions.
                startup_deadline = time.monotonic() + 10
                while not ready.exists() and time.monotonic() < startup_deadline:
                    time.sleep(0.01)
                self.assertTrue(ready.exists(), "Fixture did not start and flush its output within 10 seconds")
                if should_pass:
                    stopped_lingering = wait_for_dependency_rejection(process, mode, 10, log, "fixture")
                    self.assertEqual(linger, stopped_lingering)
                else:
                    with self.assertRaises(RuntimeError):
                        wait_for_dependency_rejection(process, mode, 0.25, log, "fixture")
                self.assertIsNotNone(process.poll())
                self.assertIn(output, log.read_text())
            finally:
                live.stop_tree(process)
                if process.stdin is not None:
                    process.stdin.close()
                if process.stdout is not None:
                    process.stdout.close()

    def test_slow_fixture_startup_is_outside_the_observation_timeout(self):
        # Deliberately exceed the 250ms rejection-observation budget without
        # retrying: output must still be preserved and the generic failure rejected.
        self.run_streaming_fixture("Failed to start the minecraft server\nother_mod is not installed",
                                   startup_delay=0.4)

    def test_fatal_dependency_rejection_stops_a_lingering_dev_launcher(self):
        for mode, actual in (("missing", "[MISSING]"), ("incompatible", "0.1.0")):
            output = ("Missing or unsupported mandatory dependencies:\n"
                      "Mod ID: 'packetweave', Requested by: 'simply_screens', "
                      f"Expected range: '[0.1.1,)', Actual version: '{actual}'\n"
                      "Failed to start the minecraft server")
            self.run_streaming_fixture(output, mode=mode, linger=True, should_pass=True)

    def test_diagnostic_without_fatal_completion_never_passes(self):
        diagnostic = ("Mod simply_screens requires packetweave 0.1.1 or above\n"
                      "Currently, packetweave is not installed")
        self.run_streaming_fixture(diagnostic, linger=True)
        self.run_streaming_fixture(diagnostic + "\nNullPointerException", linger=False)

    def test_unrelated_fatal_download_failure_and_ready_after_rejection_never_pass(self):
        diagnostic = ("Mod simply_screens requires packetweave 0.1.1 or above\n"
                      "Currently, packetweave is not installed")
        for output in ("Failed to start the minecraft server\nother_mod is not installed",
                       "Could not download packetweave: connection timed out",
                       diagnostic + "\nFailed to start the minecraft server\nDone (0.5s)! For help, type help"):
            self.run_streaming_fixture(output)

    def test_incomplete_output_drain_never_certifies_rejection_and_keeps_log(self):
        diagnostic = ("Mod simply_screens requires packetweave 0.1.1 or above\n"
                      "Currently, packetweave is not installed\nFailed to start the minecraft server\n")
        pump = SimpleNamespace(history=[diagnostic], thread=SimpleNamespace(
            is_alive=lambda: True, join=lambda **kwargs: None))
        process = SimpleNamespace(poll=lambda: None)
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "incomplete.log"
            with patch("live_join_test.OutputPump", return_value=pump), patch("live_join_test.stop_tree"):
                with self.assertRaisesRegex(RuntimeError, "did not fully drain"):
                    wait_for_dependency_rejection(process, "missing", 1, log, "fixture")
            self.assertEqual(diagnostic, log.read_text())

    def test_generic_crashes_and_timeouts_never_pass_negative_controls(self):
        for output in ("Gradle download timed out", "NullPointerException packetweave simply_screens 0.1.1",
                       "requires PacketWeave 0.1.1 but another_mod is missing", "Server stopped",
                       "Missing or unsupported mandatory dependencies:\n"
                       "Mod ID: 'other', Requested by: 'simply_screens', Expected range: '[0.1.1,)', Actual version: '[MISSING]'\n"
                       "Mod ID: 'packetweave', Requested by: 'simply_screens', Expected range: '[0.1.1,)', Actual version: '0.1.0'",
                       "Mod simply_screens requires packetweave 0.1.1 or above\nCurrently, other is not installed"):
            with self.assertRaises(RuntimeError):
                assert_dependency_rejected(output, 1, "missing")


if __name__ == "__main__":
    unittest.main()
