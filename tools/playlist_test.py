#!/usr/bin/env python3
"""Five-target live playlist gate, using real dedicated servers and remote clients.

Production artifacts are built/verified before disposable-source instrumentation.
Receipts distinguish native-handler controls, real S2C/client-world observations,
UI smoke screenshots, chunk unload/reload and exact server restart restoration.
No test driver or SS_PLAYLIST_DIR hook belongs in production source sets.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import time
import uuid
import zipfile
import zlib

import live_join_test as live
from visual_cases import TARGETS
from visual_test import (ROOT, MODULES, PACKAGE, atomic_json, checkout_lock, launch,
                         remove_runtime_when_quiet, replace_once, scope_gradle, snapshot)
from verify_production_build import verify_production_build

SCENARIOS = (
    "setup", "play-paused", "natural-transition", "pause-hold", "resume-elapsed",
    "restart-current", "previous-slide", "queue-next", "queued-current", "queue-resume",
    "resume-before-announcement", "announcement", "announcement-resume", "ui-queue", "populated-ui",
    "clear-queue", "late-join", "unloaded-timeline", "chunk-reload", "save-paused",
    "paused-restart", "resume-for-restart", "running-restart",
)
RESTORE_FIELDS = ("status", "image", "offset", "revision", "playlist", "announcing", "queued", "queueSize", "repeat")
LAUNCHER_PATCHES = (
    "PATCH 5.2.91 dev/architectury/transformer/transformers/classpath/ReadClasspathProviderImpl",
    "PATCH 5.2.91 dev/architectury/transformer/shadowed/impl/dev/architectury/tinyremapper/TinyRemapper",
)


def instrument(stage: Path) -> None:
    for module in MODULES:
        package = stage / module / PACKAGE
        drivers = package / "testing/playlist"
        drivers.mkdir(parents=True)
        legacy = module == "common-1.20.1"
        newest = module == "neoforge-26.1.2"
        for name in ("PlaylistServer", "PlaylistClient"):
            source = (stage / "tools/playlist" / (name + ".java")).read_text(encoding="utf-8")
            source = source.replace("__TELEPORT__", "player.teleportTo(level, x, y, z, Set.of(), 0.0f, 0.0f" + (", true" if newest else "") + ");")
            chunk_status = "net.minecraft.world.level.chunk.ChunkStatus.FULL" if legacy else "net.minecraft.world.level.chunk.status.ChunkStatus.FULL"
            source = source.replace("__CHUNK_LOADED__", f"mc.level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, {chunk_status}, false) != null")
            capture = "Screenshot.takeScreenshot(mc.getMainRenderTarget(), pixels -> save(pixels, stem));" if newest else "save(Screenshot.takeScreenshot(mc.getMainRenderTarget()), stem);"
            source = source.replace("__CAPTURE__", capture)
            source = source.replace("__RESIZE__", "ui.resize(" + ("" if newest else "mc, ")
                                    + "mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());")
            if re.search(r"__[A-Z_]+__", source):
                raise RuntimeError(f"unexpanded playlist driver placeholder: {name}/{module}")
            (drivers / (name + ".java")).write_text(source, encoding="utf-8")
        replace_once(package / "ServerTickScheduler.java",
                     "com.nstut.simplyscreens.helpers.ScreenPlaylistRuntime.tick(server);",
                     "com.nstut.simplyscreens.helpers.ScreenPlaylistRuntime.tick(server);\n"
                     "        if (System.getenv(\"SS_PLAYLIST_DIR\") != null) com.nstut.simplyscreens.testing.playlist.PlaylistServer.tick(server);")
        replace_once(package / "helpers/ScreenPlaylistRuntime.java", "public static void shutdown() {",
                     "public static void shutdown() {\n"
                     "        if (System.getenv(\"SS_PLAYLIST_DIR\") != null) com.nstut.simplyscreens.testing.playlist.PlaylistServer.beforeShutdown(service);")
        replace_once(package / "client/testing/UiSmokeTest.java", "public static boolean tick(Minecraft client) {",
                     "public static boolean tick(Minecraft client) {\n"
                     "        if (System.getenv(\"SS_PLAYLIST_DIR\") != null) return com.nstut.simplyscreens.testing.playlist.PlaylistClient.tick(client);")
        replace_once(package / "client/screens/ImageLoadScreen.java", "public void smokeSelectTab(int index) {",
                     "public int[] playlistProbeCounts() { return new int[]{savedPlaylists.get().size(), savedSlides.get().size(), queuedSlides.get().size(), images.get().size()}; }\n"
                     "    public void smokeSelectTab(int index) {")
    # Stable offline identity across late joins and server restarts on all five loaders.
    for target in TARGETS:
        build = stage / target / "build.gradle"
        if target == "neoforge-26.1.2":
            replace_once(build, "programArguments.addAll '--quickPlayMultiplayer', '127.0.0.1:25575'",
                         "programArguments.addAll '--quickPlayMultiplayer', '127.0.0.1:25575', '--username', 'PlaylistVerifier', '--width', '960', '--height', '720'")
        else:
            replace_once(build, 'programArgs "--quickPlayMultiplayer", "127.0.0.1:25575"',
                         'programArgs "--quickPlayMultiplayer", "127.0.0.1:25575", "--username", "PlaylistVerifier", "--width", "960", "--height", "720"')
            # Repair only the proven Architectury development-launcher leak.
            # No production jar, original Gradle file, game class or mod pool changes.
            with build.open("a", encoding="utf-8") as file:
                file.write('\ntasks.withType(JavaExec).configureEach {\n'
                           '    if (name in ["runLiveJoinTestClient", "runLiveJoinTestServer"]) {\n'
                           '        jvmArgs "-javaagent:" + rootProject.file("build/playlist-dev-launcher/agent.jar").absolutePath\n'
                           '    }\n}\n')


def build_launcher_agent(stage: Path) -> Path:
    """Compile a standalone JDK-only test agent, never part of a mod source set."""
    javac = shutil.which("javac")
    if javac is None:
        raise RuntimeError("A JDK javac is required for the playlist development-launcher agent")
    output = stage / "build/playlist-dev-launcher"
    classes = output / "classes"; classes.mkdir(parents=True)
    subprocess.run([javac, "--release", "17", "-d", str(classes),
                    str(stage / "tools/playlist/DevLauncherAgent.java")], check=True)
    jar = output / "agent.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: com.nstut.simplyscreens.testing.playlist.DevLauncherAgent\n\n")
        for path in sorted(classes.rglob("*.class")):
            archive.write(path, path.relative_to(classes).as_posix())
    return jar


def validate_launcher_patches(evidence: Path, target: str, pid: int) -> list[str]:
    if target == "neoforge-26.1.2":
        return []  # ModDevGradle does not use Architectury's development transformer.
    lines = (evidence / f"launcher-agent-{pid}.txt").read_text().splitlines()
    if len(lines) != 2 or set(lines) != set(LAUNCHER_PATCHES):
        raise ValueError("Missing, duplicate, failed or unexpected Architectury launcher patch")
    return sorted(lines)


def png_fixture(path: Path, color: tuple[int, int, int]) -> None:
    """Small deterministic PNG fixtures, with no external Python image dependency."""
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    rows = b"".join(b"\x00" + bytes((*color, 255)) * 32 for _ in range(32))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 32, 8, 6, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def png_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as file:
        header = file.read(24)
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError(f"Invalid screenshot PNG: {path}")
    return struct.unpack(">II", header[16:24])


def assert_restore(shutdown: dict, boot: dict, expected_status: str) -> None:
    required = set(RESTORE_FIELDS) | {"boot"}
    if not required <= shutdown.keys() or not required <= boot.keys():
        raise ValueError("Incomplete restart cursor receipt")
    for record in (shutdown, boot):
        if any(type(record.get(field)) is not int or record[field] < 0 for field in ("offset", "revision", "queueSize")):
            raise ValueError("Invalid restart numeric state")
        if record["queueSize"] > 64 or type(record["announcing"]) is not bool or type(record["queued"]) is not bool:
            raise ValueError("Invalid restart queue state")
    if shutdown.get("boot") == boot.get("boot"):
        raise ValueError("Restart did not produce a new server process identity")
    if shutdown.get("status") != expected_status or not shutdown.get("playlist"):
        raise ValueError("Shutdown did not preserve the required playback source/status")
    if any(shutdown.get(key) != boot.get(key) for key in RESTORE_FIELDS):
        raise ValueError("Persisted cursor changed across restart before the first elapsed server tick")


def stop_server_for_restart(process: subprocess.Popen[str], timeout=90) -> None:
    """Wait for the test driver's normal Minecraft halt(false), then natural exit.

    Keeping the input pipe open can leave Gradle's input forwarder alive after
    the server has finished. Never certify a restart by terminating that process.
    The caller's failure cleanup may stop a hung process, but cannot turn it green.
    """
    if process.poll() is not None or process.stdin is None:
        raise RuntimeError("Playlist server exited before the requested clean shutdown")
    process.stdin.close()
    process.stdin = None
    try:
        code = process.wait(timeout=timeout)
    except subprocess.TimeoutExpired as error:
        raise RuntimeError("Playlist server did not exit naturally after halt request and console EOF") from error
    if code != 0:
        raise RuntimeError(f"Failed server shutdown (exit {code}) cannot certify persistence")


def capture_shutdown_diagnostics(process: subprocess.Popen[str], evidence: Path) -> None:
    """Observe the dedicated JVM and its launcher separately; never alter either."""
    report = {"wrapperPid": process.pid, "wrapperExit": process.poll()}
    try:
        boot = json.loads((evidence / "boot.json").read_text())
        pid = boot.get("serverPid")
        if type(pid) is not int or pid <= 0:
            raise ValueError("Missing dedicated JVM PID in boot receipt")
        report["serverPid"] = pid
        if os.name != "nt":
            report["serverProcessPresent"] = Path(f"/proc/{pid}").exists()
            result = subprocess.run(["ps", "-eo", "pid,ppid,stat,comm"], capture_output=True, text=True, timeout=10)
            (evidence / "shutdown-process-tree.txt").write_text(result.stdout + result.stderr)
        java = Path(f"/proc/{pid}/exe")
        jcmd = java.resolve().with_name("jcmd") if java.exists() else None
        if jcmd is None or not jcmd.is_file():
            executable = shutil.which("jcmd")
            jcmd = Path(executable) if executable else None
        if jcmd is not None:
            result = subprocess.run([str(jcmd), str(pid), "Thread.print", "-l"],
                                    capture_output=True, text=True, timeout=20)
            report["threadDumpExit"] = result.returncode
            (evidence / "shutdown-thread-dump.txt").write_text(result.stdout + result.stderr)
    except Exception as error:
        report["diagnosticError"] = str(error)
    atomic_json(evidence / "shutdown-diagnostics.json", report)


def validate_pair(directory: Path, scenario: str, server: dict, client: dict) -> None:
    required = {"id", "action", "boot", "connection", "status", "image", "offset", "revision", "positions", "unloaded", "controlPath"}
    if not required <= server.keys() or not required <= client.keys():
        raise ValueError("Incomplete server/client receipt")
    if server["status"] not in ("STOPPED", "PAUSED", "PLAYING") or not server["boot"] or server["connection"] < 1:
        raise ValueError("Invalid server session state")
    if server.get("id") != scenario or client.get("id") != scenario:
        raise ValueError("Scenario receipt identity mismatch")
    if any(server.get(key) != client.get(key) for key in ("boot", "connection", "image", "offset", "revision", "status", "action")):
        raise ValueError("Client receipt did not acknowledge the authoritative server state")
    if not client.get("client") or client.get("stableClientTicks", 0) < 3:
        raise ValueError("Missing stable remote-client observation")
    if server.get("controlPath") != "ScreenPlaylistRuntime.handle":
        raise ValueError("Control did not pass through the native authorization handler")
    positions = server.get("positions", [])
    if len(positions) != 2 or abs(positions[0][0] - positions[1][0]) != 128:
        raise ValueError("Missing two distant linked screen positions")
    if server.get("unloaded"):
        if server.get("serverChunksUnloaded") is not True or client.get("clientChunksUnloaded") is not True or client.get("observedScreens") != 0:
            raise ValueError("Chunk-unload case retained a server or client screen chunk")
        if server.get("status") != "PLAYING" or not (server.get("revision", 0) > server.get("beforeRevision", 0)
                                                     or server.get("offset", 0) > server.get("beforeOffset", 0)):
            raise ValueError("Unloaded timeline did not advance")
    elif client.get("observedScreens") != 2 or (server.get("image") is not None and client.get("textureReady") is not True):
        raise ValueError("Linked client screens or texture were not synchronized")
    if scenario == "populated-ui":
        expected = {f"{scenario}-{tab}-{size}.png" for tab in ("gallery", "playlist", "queue") for size in ("normal", "narrow")}
        if set(client.get("screenshots", [])) != expected:
            raise ValueError("Missing populated normal/narrow UI screenshots")
        rows = client.get("visibleRows", {})
        if set(rows) != expected or any(type(count) is not int or count < 1 for count in rows.values()):
            raise ValueError("Populated gallery/playlist/queue has no fully visible list row")
        for name in expected:
            width, height = png_size(directory / name)
            if width < 300 or height < 200:
                raise ValueError("UI screenshot viewport is too small")


class LiveRun:
    def __init__(self, stage: Path, target: str, evidence: Path):
        self.stage, self.target, self.evidence = stage, target, evidence
        self.runtime = (stage / target / "run/live-join").resolve()
        self.env = os.environ | {"SS_PLAYLIST_DIR": str(evidence.resolve())}
        self.server = self.client = None
        self.logs = []
        self.server_runs = self.client_runs = 0

    def health(self, *, need_client=True):
        for name in ("server-fail.txt", "client-fail.txt"):
            path = self.evidence / name
            if path.exists(): raise RuntimeError(path.read_text())
        if self.server is None or self.server.poll() is not None:
            raise RuntimeError("Playlist dedicated server exited unexpectedly")
        if need_client and (self.client is None or self.client.poll() is not None):
            raise RuntimeError("Playlist remote client exited unexpectedly")

    def wait(self, predicate, timeout=240, *, need_client=True):
        deadline = time.monotonic() + timeout
        while not predicate():
            self.health(need_client=need_client)
            if time.monotonic() > deadline: raise TimeoutError("Playlist live scenario deadline expired")
            time.sleep(0.2)
        self.health(need_client=need_client)

    def start_server(self):
        boot_path = self.evidence / "boot.json"
        previous_boot = json.loads(boot_path.read_text()).get("boot") if boot_path.exists() else None
        self.server_runs += 1
        path = self.evidence / f"server-process-{self.server_runs}.log"
        log = path.open("w", encoding="utf-8"); self.logs.append(log)
        self.server = launch(live.command(self.stage, f":{self.target}:runLiveJoinTestServer"), self.stage, log, self.env)
        self.wait(lambda: any(marker in path.read_text(errors="replace") for marker in live.SERVER_READY_MARKERS), 480, need_client=False)
        self.wait(lambda: boot_path.exists() and json.loads(boot_path.read_text()).get("boot") != previous_boot,
                  need_client=False)
        validate_launcher_patches(self.evidence, self.target, json.loads(boot_path.read_text())["serverPid"])

    def start_client(self):
        self.client_runs += 1
        log = (self.evidence / f"client-process-{self.client_runs}.log").open("w", encoding="utf-8"); self.logs.append(log)
        command = live.command(self.stage, f":{self.target}:runLiveJoinTestClient")
        if os.name != "nt" and not os.environ.get("DISPLAY"):
            command = ["xvfb-run", "-a", "-s", "-screen 0 1280x1024x24", *command]
        self.client = launch(command, self.stage, log, self.env)

    def scenario(self, name, action, **expected):
        request = dict(id=name, action=action, **expected)
        atomic_json(self.evidence / "request.json", request)
        self.wait(lambda: (self.evidence / f"server-{name}.json").exists() and (self.evidence / f"client-{name}.json").exists())
        server = json.loads((self.evidence / f"server-{name}.json").read_text())
        client = json.loads((self.evidence / f"client-{name}.json").read_text())
        validate_pair(self.evidence, name, server, client)
        print(f"{self.target}/{name}: pass ({server['status']}, offset={server['offset']})", flush=True)
        return server, client

    def late_join(self):
        previous = json.loads((self.evidence / "client-clear-queue.json").read_text())
        live.stop_tree(self.client); self.client = None
        # Wait for the actual server logout before reusing the same offline identity.
        time.sleep(2)
        atomic_json(self.evidence / "request.json", dict(id="late-join", action="LATE_JOIN"))
        self.start_client()
        server, client = self.scenario("late-join", "LATE_JOIN")
        if server["connection"] <= previous["connection"] or client["client"] == previous["client"]:
            raise ValueError("Late join reused an old client or server player session")
        return server, client

    def restart(self, name, status):
        old_state = json.loads((self.evidence / "boot.json").read_text())
        old_boot = old_state["boot"]
        patches = validate_launcher_patches(self.evidence, self.target, old_state["serverPid"])
        live.stop_tree(self.client); self.client = None
        atomic_json(self.evidence / f"stop-{old_boot}.json", {"boot": old_boot})
        try:
            stop_server_for_restart(self.server)
        except Exception:
            capture_shutdown_diagnostics(self.server, self.evidence)
            raise
        self.server = None
        shutdown = json.loads((self.evidence / f"shutdown-{old_boot}.json").read_text())
        atomic_json(self.evidence / "request.json", dict(id=name, action="RESTART_READ"))
        self.start_server()
        self.wait(lambda: (self.evidence / "boot.json").exists()
                  and json.loads((self.evidence / "boot.json").read_text()).get("boot") != old_boot, need_client=False)
        boot = json.loads((self.evidence / "boot.json").read_text())
        assert_restore(shutdown, boot, status)
        self.start_client()
        pair = self.scenario(name, "RESTART_READ")
        return dict(status="pass", shutdown=shutdown, boot=boot, exit_code=0, launcher_patches=patches), pair

    def finish(self):
        (self.evidence / "finish.txt").write_text("done", encoding="utf-8")
        self.client.wait(timeout=60)
        if self.client.returncode != 0: raise RuntimeError("Client failed after the last observation")
        for name in ("server-fail.txt", "client-fail.txt"):
            if (self.evidence / name).exists(): raise RuntimeError((self.evidence / name).read_text())

    def close(self):
        if self.client is not None: live.stop_tree(self.client)
        if self.server is not None: live.stop_tree(self.server, graceful_server=True)
        for log in self.logs: log.close()
        for side in ("server", "client"):
            for name in ("logs", "crash-reports"):
                source = self.runtime / side / name
                if source.exists(): shutil.copytree(source, self.evidence / side / name, dirs_exist_ok=True)


def run(root: Path, target: str, compile_only=False):
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip())
    run_id = uuid.uuid4().hex
    evidence = root / "build/screen-playlist-evidence" / target / run_id
    stage = root / "build/screen-playlist-work" / target / run_id
    evidence.mkdir(parents=True)
    receipt = dict(head=head, dirty=dirty, target=target, run_id=run_id, status="running", scenarios={}, restarts={})
    started = time.monotonic(); live_run = None
    try:
        verify_production_build(root, target)
        receipt["production_verified"] = True
        receipt["source_sha256"] = snapshot(root, stage)
        scope_gradle(stage, target)
        instrument(stage)
        subprocess.run(live.command(stage, f":{target}:classes"), cwd=stage, check=True)
        if compile_only:
            receipt["status"] = "compile-only"
            return
        if target != "neoforge-26.1.2": build_launcher_agent(stage)
        standalone = live.packetweave_jar(root, target)
        receipt["packetweave"] = dict(version=live.native_version(standalone, target), sha256=hashlib.sha256(standalone.read_bytes()).hexdigest())
        live_run = LiveRun(stage, target, evidence)
        if not live_run.runtime.is_relative_to(stage.resolve()): raise RuntimeError("Unsafe playlist runtime cleanup")
        remove_runtime_when_quiet(live_run.runtime)
        live.prepare_server(stage / target); live.prepare_client(stage / target)
        live.install_packetweave(stage / target, target, standalone)
        with (live_run.runtime / "server/server.properties").open("a") as file:
            file.write("server-ip=127.0.0.1\nlevel-type=minecraft:flat\nlevel-seed=1\nview-distance=16\nsimulation-distance=2\ngamemode=creative\n")
        with (live_run.runtime / "client/options.txt").open("a") as file:
            file.write("renderDistance:16\nmaxFps:60\nfullscreen:false\ntutorialStep:none\n")
        for name, color in {"a": (240, 30, 30), "b": (30, 240, 30), "c": (30, 30, 240), "x": (240, 240, 30)}.items():
            png_fixture(evidence / f"fixture-{name}.png", color)
        live_run.start_server(); live_run.start_client()
        def step(name, action, **expected):
            pair = live_run.scenario(name, action, **expected)
            receipt["scenarios"][name] = "pass"; atomic_json(evidence / "result.json", receipt); return pair
        step("setup", "SETUP")
        step("play-paused", "PLAY_PAUSED", expectedImage="a", expectedStatus="PAUSED", expectedOffsetExact=0)
        step("natural-transition", "NATURAL", expectedImage="b", expectedStatus="PAUSED")
        step("pause-hold", "HOLD", expectedImage="b", expectedStatus="PAUSED")
        step("resume-elapsed", "RESUME_FOR", expectedImage="b", expectedStatus="PAUSED")
        step("restart-current", "RESTART", expectedImage="b", expectedStatus="PAUSED", expectedOffsetExact=0)
        step("previous-slide", "PREVIOUS", expectedImage="a", expectedStatus="PAUSED", expectedOffsetExact=0)
        step("queue-next", "QUEUE_NEXT", expectedImage="a", expectedStatus="PAUSED")
        queued, _ = step("queued-current", "NEXT", expectedImage="x", expectedStatus="PAUSED")
        if not queued["queued"]: raise ValueError("Queued image did not use the temporary queue")
        resumed, _ = step("queue-resume", "NEXT", expectedImage="b", expectedStatus="PAUSED")
        if resumed["queued"]: raise ValueError("Queue did not resume canonical order")
        step("resume-before-announcement", "RESUME_FOR", expectedImage="b", expectedStatus="PAUSED")
        announcement, _ = step("announcement", "ANNOUNCE", expectedImage="x", expectedStatus="PLAYING")
        if not announcement["announcing"]: raise ValueError("Announcement did not interrupt the source")
        step("announcement-resume", "WAIT_ANNOUNCEMENT", expectedImage="b", expectedStatus="PAUSED", expectedOffset=announcement["beforeOffset"])
        step("ui-queue", "QUEUE_NEXT", expectedImage="b", expectedStatus="PAUSED")
        step("populated-ui", "UI", expectedImage="b", expectedStatus="PAUSED")
        step("clear-queue", "CLEAR_QUEUE", expectedImage="b", expectedStatus="PAUSED")
        live_run.late_join(); receipt["scenarios"]["late-join"] = "pass"
        step("unloaded-timeline", "AWAY", expectedStatus="PLAYING")
        step("chunk-reload", "RETURN", expectedStatus="PAUSED")
        step("save-paused", "SAVE", expectedStatus="PAUSED")
        receipt["restarts"]["paused"], _ = live_run.restart("paused-restart", "PAUSED")
        receipt["scenarios"]["paused-restart"] = "pass"
        step("resume-for-restart", "RESUME", expectedStatus="PLAYING")
        receipt["restarts"]["running"], _ = live_run.restart("running-restart", "PLAYING")
        receipt["scenarios"]["running-restart"] = "pass"
        live_run.finish()
        if set(receipt["scenarios"]) != set(SCENARIOS): raise ValueError("Incomplete scenario coverage")
        receipt["status"] = "pass"
    except BaseException as error:
        receipt["status"] = "fail"; receipt["error"] = str(error); raise
    finally:
        if live_run is not None: live_run.close()
        receipt["elapsed_seconds"] = time.monotonic() - started
        atomic_json(evidence / "result.json", receipt)
        print(f"Playlist evidence: {evidence}", flush=True)


def verify_receipts(directory: Path, head: str) -> None:
    receipts = [(path, json.loads(path.read_text())) for path in directory.rglob("result.json")]
    if len(receipts) != len(TARGETS) or {r.get("target") for _, r in receipts} != set(TARGETS):
        raise ValueError("Missing, duplicate or extra playlist target receipts")
    for path, receipt in receipts:
        if receipt.get("head") != head or receipt.get("dirty") is not False or receipt.get("status") != "pass" or receipt.get("production_verified") is not True:
            raise ValueError("Stale, dirty, failed or unverified-production playlist receipt")
        if receipt.get("scenarios") != dict.fromkeys(SCENARIOS, "pass"):
            raise ValueError("Missing required playlist scenarios")
        dependency = receipt.get("packetweave", {})
        if dependency.get("version") != "0.1.1" or not re.fullmatch(r"[a-f0-9]{64}", dependency.get("sha256", "")):
            raise ValueError("Missing standalone PacketWeave runtime evidence")
        if not re.fullmatch(r"[a-f0-9]{64}", receipt.get("source_sha256", "")):
            raise ValueError("Missing frozen-source identity")
        for name, status in (("paused", "PAUSED"), ("running", "PLAYING")):
            restart = receipt.get("restarts", {}).get(name, {})
            if restart.get("status") != "pass": raise ValueError("Missing exact restart evidence")
            expected_patches = [] if receipt["target"] == "neoforge-26.1.2" else sorted(LAUNCHER_PATCHES)
            if restart.get("exit_code") != 0 or restart.get("launcher_patches") != expected_patches:
                raise ValueError("Missing natural shutdown or scoped development-launcher evidence")
            assert_restore(restart.get("shutdown", {}), restart.get("boot", {}), status)
        for scenario in SCENARIOS:
            server = json.loads((path.parent / f"server-{scenario}.json").read_text())
            client = json.loads((path.parent / f"client-{scenario}.json").read_text())
            validate_pair(path.parent, scenario, server, client)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=TARGETS)
    parser.add_argument("--compile-only", action="store_true")
    parser.add_argument("--matrix", action="store_true")
    parser.add_argument("--verify-receipts", type=Path)
    parser.add_argument("--head")
    args = parser.parse_args()
    if args.matrix: print(json.dumps({"target": TARGETS})); return
    if args.verify_receipts:
        if not args.head: parser.error("--head is required")
        verify_receipts(args.verify_receipts, args.head); return
    if not args.target: parser.error("--target is required")
    with checkout_lock(ROOT): run(ROOT, args.target, args.compile_only)


if __name__ == "__main__":
    main()
