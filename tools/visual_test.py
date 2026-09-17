#!/usr/bin/env python3
"""Real-world pixel regression gate. Run from the checkout to be certified.

All mutations and Java drivers live in a disposable snapshot. Runtime failures
cannot satisfy a negative control. No automatic retry of a failed assertion.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid

import live_join_test as live
from visual_cases import TARGETS, SAMPLES, cases, expected_outcome

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("common-1.20.1", "common-1.21.1", "neoforge-26.1.2")
PACKAGE = Path("src/main/java/com/nstut/simplyscreens")
TARGET_PROJECTS = {
    "fabric-1.20.1": {"common", "common-1.20.1", "fabric-1.20.1"},
    "forge-1.20.1": {"common", "common-1.20.1", "forge-1.20.1"},
    "fabric-1.21.1": {"common", "common-1.21.1", "fabric-1.21.1"},
    "neoforge-1.21.1": {"common", "common-1.21.1", "neoforge-1.21.1"},
    "neoforge-26.1.2": {"neoforge-26.1.2"},
}


def scope_gradle(stage, target):
    """Limit a disposable visual snapshot to the selected Gradle target.

    The production checkout stays untouched. This prevents Loom from remapping
    every loader/version before a single-target visual server can even start.
    """
    allowed = TARGET_PROJECTS[target]
    settings = stage / "settings.gradle"
    lines = []
    include = re.compile(r"^\s*include\s+'([^']+)'\s*$")
    for line in settings.read_text(encoding="utf-8").splitlines():
        match = include.match(line)
        if match and match.group(1) not in allowed:
            continue
        lines.append(line)
    settings.write_text("\n".join(lines) + "\n", encoding="utf-8")

    # :common is version-neutral source, but the root build still gives it a
    # Loom Minecraft coordinate from this property. Keep that coordinate in the
    # same family as the selected 1.20.1 platform instead of remapping 1.21.1.
    if target.endswith("1.20.1"):
        props = stage / "gradle.properties"
        text = props.read_text(encoding="utf-8")
        text, count = re.subn(r"(?m)^minecraft_version\s*=\s*\S+\s*$",
                              "minecraft_version = 1.20.1", text, count=1)
        if count != 1:
            raise RuntimeError("visual target scope could not set root Minecraft version")
        props.write_text(text, encoding="utf-8")


def atomic_json(path, data):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
    tmp.replace(path)


@contextmanager
def checkout_lock(root):
    path = root / "build/screen-visual.lock"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as lock:
        lock.write(b"0"); lock.flush(); lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise RuntimeError("another visual run owns this checkout") from exc
        try:
            yield
        finally:
            lock.seek(0)
            if os.name == "nt":
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def snapshot(root, destination):
    paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root).decode().split("\0")
    digest = hashlib.sha256()
    for relative in sorted(set(p for p in paths if p)):
        source = root / relative
        if not source.is_file():
            continue
        if source.is_symlink():
            raise RuntimeError("snapshot refuses symlinks")
        data = source.read_bytes()
        digest.update(relative.encode()+b"\0"+data)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    return digest.hexdigest()


def replace_once(path, old, new):
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(f"instrumentation anchor changed: {path}: {old}")
    path.write_text(text.replace(old,new), encoding="utf-8")


def instrument(stage):
    for module in MODULES:
        new = module == "neoforge-26.1.2"
        package = stage / module / PACKAGE
        drivers = package / "testing/visual"
        drivers.mkdir(parents=True)
        for name in ("VisualServer", "VisualClient"):
            source = (stage / "tools/visual" / (name+".java")).read_text(encoding="utf-8")
            source = source.replace("__TELEPORT__", "player.teleportTo(level, eye.x, eye.y-player.getEyeHeight(), eye.z, Set.of(), yaw, pitch" + (", true" if new else "") + ");")
            source = source.replace("__CAMERA_POSITION__", "camera.position()" if new else "camera.getPosition()")
            source = source.replace("__CAMERA_YAW__", "camera.yRot()" if new else "camera.getYRot()")
            source = source.replace("__CAMERA_PITCH__", "camera.xRot()" if new else "camera.getXRot()")
            capture = "Screenshot.takeScreenshot(mc.getMainRenderTarget(), pixels -> save(pixels, frame, stem));" if new else "save(Screenshot.takeScreenshot(mc.getMainRenderTarget()), frame, stem);"
            source = source.replace("__CAPTURE__", capture)
            (drivers / (name+".java")).write_text(source, encoding="utf-8")
        replace_once(package / "ServerTickScheduler.java", "private static void process(MinecraftServer server) {",
                     "private static void process(MinecraftServer server) {\n        com.nstut.simplyscreens.testing.visual.VisualServer.tick(server);")
        replace_once(package / "client/testing/UiSmokeTest.java", "public static boolean tick(Minecraft client) {",
                     "public static boolean tick(Minecraft client) {\n        if (System.getenv(\"SS_VISUAL_DIR\") != null) return com.nstut.simplyscreens.testing.visual.VisualClient.tick(client);")
        renderer = package / "client/renderers/ScreenBlockEntityRenderer.java"
        anchor = "debugDraw(state);" if new else "PoseStack.Pose pose = poseStack.last();"
        replace_once(renderer, anchor, anchor+"\n        com.nstut.simplyscreens.testing.visual.VisualClient.submitted();")


def set_variant(stage, originals, variant):
    for module, source in originals.items():
        if variant != "fixed":
            source = source.replace(".textPolygonOffset(", ".text(" if variant == "plain" else ".textSeeThrough(")
        (stage / module / PACKAGE / "client/renderers/ScreenBlockEntityRenderer.java").write_text(source, encoding="utf-8")


def health(directory, server, client):
    for name in ("server-fail.txt", "client-fail.txt"):
        path = directory / name
        if path.exists():
            raise RuntimeError(path.read_text())
    if server.poll() is not None:
        raise RuntimeError(f"server exited prematurely: {server.returncode}")
    if client.poll() is not None:
        raise RuntimeError(f"client exited prematurely: {client.returncode}")


def wait_until(predicate, directory, server, client, timeout):
    deadline = time.monotonic()+timeout
    while not predicate():
        health(directory, server, client)
        if time.monotonic() > deadline:
            raise TimeoutError("visual scenario deadline expired")
        time.sleep(0.25)
    health(directory, server, client)


def launch(command, stage, log, env):
    kwargs = dict(cwd=stage, env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.PIPE, text=True)
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    else:
        kwargs["start_new_session"] = True
    return subprocess.Popen(command, **kwargs)


def run_variant(stage, target, variant, directory, probe=False):
    from PIL import Image
    from visual_pixels import measure
    directory.mkdir(parents=True)
    Image.new("RGB", (32,32), (240,24,240)).save(directory / "fixture.png")
    # Fresh runtime only; compiled outputs/Gradle caches are reused between variants.
    runtime = (stage / target / "run/live-join").resolve()
    if not runtime.is_relative_to(stage.resolve()):
        raise RuntimeError("unsafe runtime cleanup")
    if runtime.exists(): shutil.rmtree(runtime)
    live.prepare_server(stage / target)
    live.prepare_client(stage / target)
    with (runtime / "server/server.properties").open("a") as file:
        file.write("server-ip=127.0.0.1\nlevel-type=minecraft:flat\nlevel-seed=1\nview-distance=16\nsimulation-distance=2\ngamemode=spectator\n")
    with (runtime / "client/options.txt").open("a") as file:
        file.write("renderDistance:16\nfov:0.0\nbobView:false\nmaxFps:60\nfullscreen:false\n")
    env = os.environ | {"SS_VISUAL_DIR": str(directory.resolve())}
    server_cmd = live.command(stage, f":{target}:runLiveJoinTestServer")
    client_cmd = live.command(stage, f":{target}:runLiveJoinTestClient")
    if os.name != "nt" and not os.environ.get("DISPLAY"):
        client_cmd = ["xvfb-run", "-a", "-s", "-screen 0 1920x1080x24", *client_cmd]
    results = {}
    graphics = None
    server = client = None
    with (directory / "server-process.log").open("w", encoding="utf-8") as server_log, (directory / "client-process.log").open("w", encoding="utf-8") as client_log:
        try:
            server = launch(server_cmd, stage, server_log, env)
            deadline = time.monotonic()+360
            while "Done (" not in (directory / "server-process.log").read_text(errors="replace"):
                if server.poll() is not None: raise RuntimeError("server failed during startup")
                if time.monotonic() > deadline: raise TimeoutError("server startup timeout")
                time.sleep(0.5)
            client = launch(client_cmd, stage, client_log, env)
            selected = cases(variant)
            if probe:
                selected = [c for c in selected if c["occluded"] or c.get("reload")
                            or (c["facing"] == "NORTH" and c["size"] == 8 and c["distance"] in (8,64,160))
                            or (c["size"] == 8 and c["distance"] == 8 and c["angle"] == 0)
                            or (c["size"] == 2 and c["distance"] == 160)]
            for case in selected:
                atomic_json(directory / "request.json", case)
                wait_until(lambda: (directory / f'{case["id"]}-{SAMPLES-1:02d}.json').exists(), directory, server, client, 240)
                frames = []
                for index in range(SAMPLES):
                    stem = f'{case["id"]}-{index:02d}'
                    frame = json.loads((directory / (stem+".json")).read_text())
                    if any(frame.get(k) != v for k,v in case.items()) or frame["sample"] != index or frame["submissions"] <= 0:
                        raise RuntimeError("frame belongs to a different/incomplete scenario")
                    if case.get("reload") and frame.get("reloaded") != (index >= 8):
                        raise RuntimeError("missing before/after resource reload evidence")
                    identity = {k: frame.get(k) for k in ("graphicsVendor", "graphicsRenderer", "graphicsVersion")}
                    if not all(identity.values()) or (graphics is not None and graphics != identity):
                        raise RuntimeError("missing or changing graphics backend identity")
                    graphics = identity
                    atomic_json(directory / "graphics.json", graphics)
                    frames.append(measure(directory / (stem+".png"), frame))
                result = dict(status="pass" if all(f["status"] == "pass" for f in frames) else "pixel-failure",
                              image_errors=max(f["image_errors"] for f in frames),
                              occlusion_errors=max(f["occlusion_errors"] for f in frames), frames=frames)
                results[case["id"]] = result
                atomic_json(directory / "measurements.json", results)
                print(f'{target}/{variant}/{case["id"]}: {result["status"]} image={result["image_errors"]:.4f} occlusion={result["occlusion_errors"]:.4f}', flush=True)
            (directory / "finish.txt").write_text("done")
            client.wait(timeout=60)
            if client.returncode != 0: raise RuntimeError("client nonzero exit overrides results")
            for name in ("server-fail.txt", "client-fail.txt"):
                if (directory / name).exists(): raise RuntimeError((directory / name).read_text())
            if not probe:
                expected_outcome(variant, results)
        finally:
            if client is not None: live.stop_tree(client)
            if server is not None: live.stop_tree(server, graceful_server=True)
            for side in ("server", "client"):
                for folder in ("logs", "crash-reports"):
                    source = runtime / side / folder
                    if source.exists(): shutil.copytree(source, directory / side / folder, dirs_exist_ok=True)
    return results


def run(root, target, compile_only=False, probe=False):
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip())
    run_id = uuid.uuid4().hex
    evidence = root / "build/screen-visual-evidence" / target / run_id
    stage = root / "build/screen-visual-work" / target / run_id
    evidence.mkdir(parents=True)
    receipt = dict(head=head, dirty=dirty, target=target, run_id=run_id, status="running", variants={})
    started = time.monotonic()
    try:
        receipt["source_sha256"] = snapshot(root, stage)
        scope_gradle(stage, target)
        instrument(stage)
        originals = {m: (stage / m / PACKAGE / "client/renderers/ScreenBlockEntityRenderer.java").read_text() for m in MODULES}
        # Fixed viewport is also passed to the launcher (options alone do not resize the window).
        for target_name in TARGETS:
            build = stage / target_name / "build.gradle"
            if target_name == "neoforge-26.1.2":
                replace_once(build, "programArguments.addAll '--quickPlayMultiplayer', '127.0.0.1:25575'", "programArguments.addAll '--quickPlayMultiplayer', '127.0.0.1:25575', '--width', '1920', '--height', '1080'")
            else:
                replace_once(build, 'programArgs "--quickPlayMultiplayer", "127.0.0.1:25575"', 'programArgs "--quickPlayMultiplayer", "127.0.0.1:25575", "--width", "1920", "--height", "1080"')
        if compile_only:
            subprocess.run(live.command(stage, f":{target}:classes"), cwd=stage, check=True)
            receipt["status"] = "compile-only"
        else:
            for variant in ("fixed", "plain", "see-through"):
                set_variant(stage, originals, variant)
                run_variant(stage, target, variant, evidence / variant, probe)
                graphics = json.loads((evidence / variant / "graphics.json").read_text())
                if "graphics" in receipt and receipt["graphics"] != graphics:
                    raise RuntimeError("negative control ran on a different graphics backend")
                receipt["graphics"] = graphics
                receipt["variants"][variant] = "diagnostic" if probe else "pass"
            receipt["status"] = "probe-only" if probe else "pass"
    except BaseException as exc:
        receipt["status"] = "fail"
        receipt["error"] = str(exc)
        raise
    finally:
        receipt["elapsed_seconds"] = time.monotonic()-started
        atomic_json(evidence / "result.json", receipt)
        print(f"Visual evidence: {evidence}", flush=True)


def verify_receipts(directory, head):
    receipts = [json.loads(p.read_text()) for p in directory.rglob("result.json")]
    if len(receipts) != len(TARGETS) or {r.get("target") for r in receipts} != set(TARGETS):
        raise ValueError("missing, duplicate or extra target receipts")
    for r in receipts:
        if r.get("head") != head or r.get("dirty") is not False or r.get("status") != "pass":
            raise ValueError("stale, dirty or failed visual receipt")
        if r.get("variants") != {v:"pass" for v in ("fixed", "plain", "see-through")}:
            raise ValueError("missing negative controls")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=TARGETS)
    parser.add_argument("--compile-only", action="store_true")
    parser.add_argument("--probe", action="store_true", help="small diagnostic subset; cannot produce a passing gate receipt")
    parser.add_argument("--matrix", action="store_true")
    parser.add_argument("--verify-receipts", type=Path)
    parser.add_argument("--head")
    args = parser.parse_args()
    if args.matrix:
        print(json.dumps({"target": TARGETS})); return
    if args.verify_receipts:
        if not args.head: parser.error("--head is required")
        verify_receipts(args.verify_receipts, args.head); return
    if not args.target: parser.error("--target is required")
    with checkout_lock(ROOT): run(ROOT, args.target, args.compile_only, args.probe)


if __name__ == "__main__":
    main()
