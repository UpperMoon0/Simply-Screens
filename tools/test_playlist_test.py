import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import Mock, patch

import playlist_test as harness


class PlaylistHarnessTests(unittest.TestCase):
    def pair(self, scenario="pause-hold"):
        server = dict(id=scenario, action="HOLD", boot="server-1", connection=1, status="PAUSED",
                      image="image-a", offset=600, revision=4, positions=[[2048, 128, 0], [2176, 128, 0]],
                      unloaded=False, serverChunksUnloaded=False, controlPath="ScreenPlaylistRuntime.handle")
        return server, server | dict(client="client-1", stableClientTicks=3, observedScreens=2,
                                    clientChunksUnloaded=False, textureReady=True, screenshots=[])

    def state(self, boot="server-1", status="PAUSED"):
        return dict(boot=boot, status=status, image="a", offset=537, revision=7, playlist="list",
                    announcing=False, queued=False, queueSize=0, repeat="LIST")

    def test_target_and_scenario_matrix_are_complete_unique(self):
        self.assertEqual(5, len(harness.TARGETS))
        self.assertEqual(23, len(harness.SCENARIOS))
        self.assertEqual(len(set(harness.SCENARIOS)), len(harness.SCENARIOS))
        self.assertIn("unloaded-timeline", harness.SCENARIOS)
        self.assertIn("late-join", harness.SCENARIOS)
        self.assertIn("paused-restart", harness.SCENARIOS)
        self.assertIn("running-restart", harness.SCENARIOS)
        self.assertIn("populated-ui", harness.SCENARIOS)

    def test_live_pair_requires_two_distant_actual_client_screens_and_texture(self):
        with tempfile.TemporaryDirectory() as tmp:
            server, client = self.pair()
            harness.validate_pair(Path(tmp), "pause-hold", server, client)
            for field, value in (("observedScreens", 1), ("textureReady", False), ("stableClientTicks", 0),
                                 ("boot", "another-server"), ("revision", 99), ("id", "stale")):
                with self.subTest(field=field), self.assertRaises(ValueError):
                    harness.validate_pair(Path(tmp), "pause-hold", server, client | {field: value})
            del server["image"]
            with self.assertRaises(ValueError): harness.validate_pair(Path(tmp), "pause-hold", server, client)

    def test_unload_requires_real_server_and_client_chunks_absent_and_timeline_progress(self):
        with tempfile.TemporaryDirectory() as tmp:
            server, client = self.pair("unloaded-timeline")
            server |= dict(status="PLAYING", unloaded=True, serverChunksUnloaded=True, beforeOffset=100, beforeRevision=3)
            client |= server | dict(observedScreens=0, clientChunksUnloaded=True)
            harness.validate_pair(Path(tmp), "unloaded-timeline", server, client)
            with self.assertRaises(ValueError):
                harness.validate_pair(Path(tmp), "unloaded-timeline", server | {"serverChunksUnloaded": False}, client)
            with self.assertRaises(ValueError):
                harness.validate_pair(Path(tmp), "unloaded-timeline", server, client | {"clientChunksUnloaded": False})
            with self.assertRaises(ValueError):
                harness.validate_pair(Path(tmp), "unloaded-timeline", server | dict(beforeOffset=600, beforeRevision=4), client)

    def test_exact_restart_evidence_rejects_downtime_or_missing_state(self):
        shutdown = self.state()
        boot = self.state("server-2")
        harness.assert_restore(shutdown, boot, "PAUSED")
        for field, value in (("offset", 600), ("revision", 8), ("image", "b"), ("status", "PLAYING"),
                             ("playlist", "wrong-list"), ("boot", "server-1")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                harness.assert_restore(shutdown, boot | {field: value}, "PAUSED")
        missing = dict(boot); del missing["offset"]
        with self.assertRaises(ValueError): harness.assert_restore(shutdown, missing, "PAUSED")
        with self.assertRaises(ValueError): harness.assert_restore(shutdown, boot, "PLAYING")
        harness.assert_restore(self.state(status="PLAYING"), self.state("server-2", "PLAYING"), "PLAYING")

    def test_restart_shutdown_closes_console_before_waiting_for_natural_exit(self):
        process = Mock()
        process.poll.return_value = None
        process.wait.return_value = 0
        stream = process.stdin
        harness.stop_server_for_restart(process)
        stream.write.assert_not_called()
        stream.close.assert_called_once_with()
        process.wait.assert_called_once_with(timeout=90)
        self.assertIsNone(process.stdin)
        self.assertLess(process.mock_calls.index(unittest.mock.call.stdin.close()),
                        process.mock_calls.index(unittest.mock.call.wait(timeout=90)))

    def test_restart_shutdown_never_certifies_timeout_or_nonzero_exit(self):
        for result in (1, -15, harness.subprocess.TimeoutExpired("server", 90)):
            process = Mock()
            process.poll.return_value = None
            if isinstance(result, Exception): process.wait.side_effect = result
            else: process.wait.return_value = result
            with self.subTest(result=result), patch.object(harness.live, "stop_tree") as forced:
                with self.assertRaises(RuntimeError): harness.stop_server_for_restart(process)
                forced.assert_not_called()
        process.poll.return_value = 0
        with self.assertRaises(RuntimeError): harness.stop_server_for_restart(process)

    def test_shutdown_diagnostics_target_boot_jvm_not_gradle_wrapper(self):
        with tempfile.TemporaryDirectory() as tmp:
            evidence = Path(tmp)
            (evidence / "boot.json").write_text(json.dumps({"serverPid": 987654321}))
            process = Mock(pid=1234); process.poll.return_value = None
            completed = harness.subprocess.CompletedProcess([], 0, "thread snapshot", "")
            with patch.object(harness.shutil, "which", return_value="/fake/jcmd"), \
                 patch.object(harness.subprocess, "run", return_value=completed) as observe:
                harness.capture_shutdown_diagnostics(process, evidence)
            commands = [call.args[0] for call in observe.call_args_list]
            self.assertIn([str(Path("/fake/jcmd")), "987654321", "Thread.print", "-l"], commands)
            report = json.loads((evidence / "shutdown-diagnostics.json").read_text())
            self.assertEqual(1234, report["wrapperPid"])
            self.assertEqual(987654321, report["serverPid"])
            self.assertIsNone(report["wrapperExit"])
            self.assertEqual("thread snapshot", (evidence / "shutdown-thread-dump.txt").read_text())

    def test_fixture_pngs_are_deterministic_and_do_not_need_pillow(self):
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a.png", Path(tmp) / "b.png"
            harness.png_fixture(a, (220, 20, 20)); harness.png_fixture(b, (220, 20, 20))
            self.assertEqual(a.read_bytes(), b.read_bytes())
            self.assertEqual((32, 32), harness.png_size(a))
            b.write_bytes(b"not a PNG")
            with self.assertRaises(ValueError): harness.png_size(b)

    def test_populated_ui_requires_all_six_real_screenshots(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            server, client = self.pair("populated-ui")
            server["action"] = client["action"] = "UI"
            client["visibleRows"] = {}
            for tab in ("gallery", "playlist", "queue"):
                for size in ("normal", "narrow"):
                    filename = f"populated-ui-{tab}-{size}.png"
                    # Only the header is relevant to this validation unit test; live captures are complete PNGs.
                    (directory / filename).write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00\x00\x00\rIHDR" + (960).to_bytes(4, "big") + (720).to_bytes(4, "big"))
                    client["screenshots"].append(filename)
                    client["visibleRows"][filename] = 1
            harness.validate_pair(directory, "populated-ui", server, client)
            for tab in ("gallery", "playlist", "queue"):
                hidden = copy.deepcopy(client)
                hidden["visibleRows"][f"populated-ui-{tab}-normal.png"] = 0
                with self.subTest(tab=tab), self.assertRaisesRegex(ValueError, "fully visible"):
                    harness.validate_pair(directory, "populated-ui", server, hidden)
            client["screenshots"].pop()
            with self.assertRaises(ValueError): harness.validate_pair(directory, "populated-ui", server, client)

    def test_instrumentation_changes_only_disposable_sources_and_expands_three_api_variants(self):
        paths = []
        for module in harness.MODULES:
            for name in ("ServerTickScheduler.java", "helpers/ScreenPlaylistRuntime.java", "client/testing/UiSmokeTest.java", "client/screens/ImageLoadScreen.java"):
                paths.append(Path(module) / harness.PACKAGE / name)
        paths += [Path(target) / "build.gradle" for target in harness.TARGETS]
        paths += [Path("tools/playlist") / (name + ".java") for name in ("PlaylistServer", "PlaylistClient")]
        before = {path: (harness.ROOT / path).read_bytes() for path in paths}
        with tempfile.TemporaryDirectory() as tmp:
            stage = Path(tmp)
            for path, contents in before.items():
                destination = stage / path; destination.parent.mkdir(parents=True, exist_ok=True); destination.write_bytes(contents)
            harness.instrument(stage)
            for module in harness.MODULES:
                package = stage / module / harness.PACKAGE
                server = (package / "testing/playlist/PlaylistServer.java").read_text()
                client = (package / "testing/playlist/PlaylistClient.java").read_text()
                self.assertNotIn("__TELEPORT__", server)
                self.assertNotIn("__CHUNK_LOADED__", client)
                self.assertNotIn("__CAPTURE__", client)
                self.assertNotIn("__RESIZE__", client)
                self.assertIn("PlaylistServer.tick(server)", (package / "ServerTickScheduler.java").read_text())
                self.assertIn("playlistProbeCounts()", (package / "client/screens/ImageLoadScreen.java").read_text())
                if module == "neoforge-26.1.2":
                    self.assertIn("0.0f, true);", server)
                    self.assertIn("pixels -> save(pixels, stem)", client)
                    self.assertIn("ui.resize(mc.getWindow()", client)
                    self.assertNotIn("ui.resize(mc,", client)
                else:
                    self.assertNotIn("0.0f, true);", server)
                    self.assertIn("save(Screenshot.takeScreenshot", client)
                    self.assertIn("ui.resize(mc, mc.getWindow()", client)
            for target in harness.TARGETS:
                build = (stage / target / "build.gradle").read_text()
                self.assertEqual(target != "neoforge-26.1.2", "-javaagent:" in build)
                if target != "neoforge-26.1.2":
                    self.assertIn('name in ["runLiveJoinTestClient", "runLiveJoinTestServer"]', build)
            with self.assertRaises((RuntimeError, FileExistsError)): harness.instrument(stage)
        for path, contents in before.items(): self.assertEqual(contents, (harness.ROOT / path).read_bytes())

    def test_production_verification_precedes_instrumentation_and_compile_only_cannot_pass_gate(self):
        events = []
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with patch.object(harness.subprocess, "check_output", side_effect=["abc123\n", ""]), \
                 patch.object(harness, "verify_production_build", side_effect=lambda *_: events.append("production")), \
                 patch.object(harness, "snapshot", side_effect=lambda *_: events.append("snapshot") or "a" * 64), \
                 patch.object(harness, "scope_gradle", side_effect=lambda *_: events.append("scope")), \
                 patch.object(harness, "instrument", side_effect=lambda *_: events.append("instrument")), \
                 patch.object(harness.subprocess, "run", side_effect=lambda *_args, **_kwargs: events.append("compile")):
                harness.run(root, "fabric-1.20.1", compile_only=True)
            self.assertEqual(["production", "snapshot", "scope", "instrument", "compile"], events)
            receipt = json.loads(next(root.rglob("result.json")).read_text())
            self.assertEqual("compile-only", receipt["status"])
            self.assertTrue(receipt["production_verified"])
            self.assertEqual({}, receipt["scenarios"])

    def test_drivers_do_not_fake_packets_assign_client_state_or_force_chunk_unload(self):
        server = (harness.ROOT / "tools/playlist/PlaylistServer.java").read_text()
        client = (harness.ROOT / "tools/playlist/PlaylistClient.java").read_text()
        self.assertIn("ScreenPlaylistRuntime.handle(player, A, request.json())", server)
        self.assertIn("level.getChunkSource().getChunkNow", server)
        self.assertIn("screen.getImageId()", client)
        self.assertIn("ClientImageManager.getTextureLocation(image)", client)
        self.assertIn("node instanceof VirtualList<?>", client)
        self.assertIn("child.getY() + child.getHeight() <= bottom", client)
        for forbidden in ("ClientboundForgetLevelChunkPacket", "setImageId(", "updateScreen(", "service.tick("):
            self.assertNotIn(forbidden, server + client)
        self.assertIn('write("shutdown-" + BOOT + ".json", receipt)', server)
        self.assertIn('write("boot-" + BOOT + ".json", boot)', server)
        self.assertIn('boot.addProperty("serverPid", ProcessHandle.current().pid())', server)
        self.assertIn('server.halt(false)', server)
        self.assertLess(server.index('server.halt(false)'), server.index('getPlayers().isEmpty()'))

    def test_verifier_rejects_missing_matrix_even_if_one_receipt_claims_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "result.json"
            path.write_text(json.dumps(dict(target="fabric-1.20.1", status="pass", head="head", dirty=False)))
            with self.assertRaises(ValueError): harness.verify_receipts(Path(tmp), "head")


if __name__ == "__main__":
    unittest.main()
