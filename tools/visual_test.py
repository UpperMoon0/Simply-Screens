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
from visual_cases import TARGETS, SAMPLES, sample_count, cases, expected_outcome, variants, NF26_TARGET

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("common-1.20.1", "common-1.21.1", "neoforge-26.1.2")
PACKAGE = Path("src/main/java/com/nstut/simplyscreens")
NF26_CONTROL = Path("tools/visual_controls/neoforge-26.1.2")
NF26_CONTROL_FILES = {
    Path("neoforge-26.1.2/src/main/java/com/nstut/simplyscreens/client/renderers/ScreenBlockEntityRenderer.java"): "ScreenBlockEntityRenderer.java",
    Path("neoforge-26.1.2/src/main/java/com/nstut/simplyscreens/client/renderers/ScreenBlockEntityRenderState.java"): "ScreenBlockEntityRenderState.java",
    Path("neoforge-26.1.2/src/main/java/com/nstut/simplyscreens/neoforge/SimplyScreensClient.java"): "SimplyScreensClient.java",
}
NF26_WORLD_MODELS = (
    Path("neoforge-26.1.2/src/main/resources/assets/simply_screens/models/block/screen.json"),
    Path("neoforge-26.1.2/src/main/resources/assets/simply_screens/models/block/screen_anchor.json"),
)
COMMON_WORLD_MODEL_PATHS = (
    Path("common/src/main/resources/assets/simply_screens/models/block/screen.json"),
    Path("common/src/main/resources/assets/simply_screens/models/block/screen_anchor.json"),
)
COMMON_WORLD_MODELS = {
    NF26_WORLD_MODELS[0]: COMMON_WORLD_MODEL_PATHS[0],
    NF26_WORLD_MODELS[1]: COMMON_WORLD_MODEL_PATHS[1],
}
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


def instrument_visual_render_events(stage):
    """Capture only after the rendered world is actually in the main framebuffer."""
    registrations = {
        "fabric-1.20.1": (
            PACKAGE / "fabric/client/ClientSetup.java",
            "BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);",
            "BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);\n"
            "        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.START.register(context -> com.nstut.simplyscreens.testing.visual.VisualClient.beginRenderFrame());\n"
            "        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.END.register(context -> com.nstut.simplyscreens.testing.visual.VisualClient.afterRender(net.minecraft.client.Minecraft.getInstance()));",
        ),
        "fabric-1.21.1": (
            PACKAGE / "fabric/client/ClientSetup.java",
            "BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);",
            "BlockEntityRenderers.register(BlockEntityRegistries.SCREEN.get(), ScreenBlockEntityRenderer::new);\n"
            "        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.START.register(context -> com.nstut.simplyscreens.testing.visual.VisualClient.beginRenderFrame());\n"
            "        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.END.register(context -> com.nstut.simplyscreens.testing.visual.VisualClient.afterRender(net.minecraft.client.Minecraft.getInstance()));",
        ),
        "forge-1.20.1": (
            PACKAGE / "forge/client/ClientSetup.java",
            "MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientDisconnect);",
            "MinecraftForge.EVENT_BUS.addListener(ClientSetup::onClientDisconnect);\n"
            "            MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.RenderLevelStageEvent visualEvent) -> {\n"
            "                if (System.getenv(\"SS_VISUAL_DIR\") == null) return;\n"
            "                if (visualEvent.getStage() == net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY)\n"
            "                    com.nstut.simplyscreens.testing.visual.VisualClient.beginRenderFrame();\n"
            "                else if (visualEvent.getStage() == net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL)\n"
            "                    com.nstut.simplyscreens.testing.visual.VisualClient.afterRender(Minecraft.getInstance());\n"
            "            });",
        ),
        "neoforge-1.21.1": (
            PACKAGE / "neoforge/client/ClientSetup.java",
            "NeoForge.EVENT_BUS.addListener(ClientSetup::onRenderLevelStage);",
            "NeoForge.EVENT_BUS.addListener(ClientSetup::onRenderLevelStage);\n"
            "            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderLevelStageEvent visualEvent) -> {\n"
            "                if (System.getenv(\"SS_VISUAL_DIR\") == null) return;\n"
            "                if (visualEvent.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY)\n"
            "                    com.nstut.simplyscreens.testing.visual.VisualClient.beginRenderFrame();\n"
            "                else if (visualEvent.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL)\n"
            "                    com.nstut.simplyscreens.testing.visual.VisualClient.afterRender(Minecraft.getInstance());\n"
            "            });",
        ),
    }
    for target, (relative, old, new) in registrations.items():
        replace_once(stage / target / relative, old, new)
    instrument_nf26_visual_events(stage)


def instrument_nf26_visual_events(stage):
    path = stage / "neoforge-26.1.2" / PACKAGE / "neoforge/SimplyScreensClient.java"
    text = path.read_text(encoding="utf-8")
    if "VisualClient.afterRender" in text:
        return
    old = "PacketRegistries.registerS2CPackets();"
    if text.count(old) != 1:
        raise RuntimeError(f"instrumentation anchor changed: {path}: {old}")
    new = old + "\n" \
        "            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterSky visualEvent) -> com.nstut.simplyscreens.testing.visual.VisualClient.beginRenderFrame());\n" \
        "            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterLevel visualEvent) -> com.nstut.simplyscreens.testing.visual.VisualClient.afterRender(Minecraft.getInstance()));"
    path.write_text(text.replace(old, new), encoding="utf-8")


def instrument(stage):
    for module in MODULES:
        new = module == "neoforge-26.1.2"
        package = stage / module / PACKAGE
        drivers = package / "testing/visual"
        drivers.mkdir(parents=True)
        for name in ("VisualServer", "VisualClient"):
            source = (stage / "tools/visual" / (name+".java")).read_text(encoding="utf-8")
            source = source.replace("__BASE_SAMPLES__", str(SAMPLES))
            source = source.replace("__TELEPORT__", "player.teleportTo(level, eye.x, eye.y-player.getEyeHeight(), eye.z, Set.of(), yaw, pitch" + (", true" if new else "") + ");")
            source = source.replace("__CAMERA_POSITION__", "camera.position()" if new else "camera.getPosition()")
            source = source.replace("__CAMERA_YAW__", "camera.yRot()" if new else "camera.getYRot()")
            source = source.replace("__CAMERA_PITCH__", "camera.xRot()" if new else "camera.getXRot()")
            legacy = module == "common-1.20.1"
            source = source.replace("__TERRAIN_IDLE__", "mc.levelRenderer.hasRenderedAllChunks()" if legacy else "mc.levelRenderer.hasRenderedAllSections()")
            compiled = "isChunkCompiled" if legacy else "isSectionCompiledAndVisible" if new else "isSectionCompiled"
            source = source.replace("__SECTION_COMPILED__", f"mc.levelRenderer.{compiled}(pos)")
            capture = "Screenshot.takeScreenshot(mc.getMainRenderTarget(), pixels -> save(pixels, frame, stem));" if new else "save(Screenshot.takeScreenshot(mc.getMainRenderTarget()), frame, stem);"
            source = source.replace("__CAPTURE__", capture)
            (drivers / (name+".java")).write_text(source, encoding="utf-8")
        replace_once(package / "ServerTickScheduler.java", "private static void process(MinecraftServer server) {",
                     "private static void process(MinecraftServer server) {\n        com.nstut.simplyscreens.testing.visual.VisualServer.tick(server);")
        replace_once(package / "client/testing/UiSmokeTest.java", "public static boolean tick(Minecraft client) {",
                     "public static boolean tick(Minecraft client) {\n        if (System.getenv(\"SS_VISUAL_DIR\") != null) return com.nstut.simplyscreens.testing.visual.VisualClient.tick(client);")
        renderer = package / "client/renderers/ScreenBlockEntityRenderer.java"
        if module == "common-1.20.1":
            anchor = "debugDraw(blockEntity, texture, facing);"
            submitted = (
                "com.nstut.simplyscreens.testing.visual.VisualClient.submittedTile(facing, "
                "renderData.getScreenWidth(), renderData.getScreenHeight(), "
                "blockEntity.getAnchorPos().getX(), blockEntity.getAnchorPos().getY(), blockEntity.getAnchorPos().getZ(), "
                "blockEntity.getBlockPos().getX(), blockEntity.getBlockPos().getY(), blockEntity.getBlockPos().getZ());"
            )
        elif module == "common-1.21.1":
            anchor = "debugDraw(blockEntity, anchorPos, texture, facing);"
            submitted = (
                "com.nstut.simplyscreens.testing.visual.VisualClient.submittedScreen(facing, "
                "renderData.getScreenWidth(), renderData.getScreenHeight(), "
                "anchorPos.getX(), anchorPos.getY(), anchorPos.getZ());"
            )
        else:
            anchor = "debugDraw(state);"
            submitted = (
                "com.nstut.simplyscreens.testing.visual.VisualClient.submittedScreen(state.facing, state.width, state.height, "
                "state.blockPos.getX() + state.anchorOffsetX, state.blockPos.getY() + state.anchorOffsetY, "
                "state.blockPos.getZ() + state.anchorOffsetZ);"
            )
        replace_once(renderer, anchor, anchor + "\n        " + submitted)
    instrument_visual_render_events(stage)


def verify_nf26_control(stage):
    manifest = json.loads((stage / NF26_CONTROL / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("source_commit") != "1ec1058a8026294df8bd34ed21e6852d0244f26c":
        raise RuntimeError("NeoForge 26 original-control provenance changed")
    expected_files = set(NF26_CONTROL_FILES.values())
    if set(manifest.get("files", {})) != expected_files:
        raise RuntimeError("NeoForge 26 original-control manifest is incomplete")
    for name, expected in manifest["files"].items():
        actual = hashlib.sha256((stage / NF26_CONTROL / name).read_bytes()).hexdigest()
        if actual != expected:
            raise RuntimeError(f"NeoForge 26 original-control fixture drifted: {name}")


def _coplanar_control_model(source):
    model = json.loads(source)
    elements = model.get("elements", [])
    body = next((element for element in elements
                 if element.get("from") == [0, 0, 0] and element.get("to") == [16, 16, 16]), None)
    if body is None:
        raise RuntimeError("cannot reconstruct coplanar control model: full block body missing")
    body = json.loads(json.dumps(body))
    body.setdefault("faces", {})["north"] = {"texture": "#front", "cullface": "north"}
    model["elements"] = [body]
    return json.dumps(model, indent=2) + "\n"


def restore_common_control_models(stage):
    for model in COMMON_WORLD_MODEL_PATHS:
        path = stage / model
        path.write_text(_coplanar_control_model(path.read_text(encoding="utf-8")), encoding="utf-8")


def restore_nf26_control_models(stage):
    for local_model, common_model in COMMON_WORLD_MODELS.items():
        destination = stage / local_model
        destination.parent.mkdir(parents=True, exist_ok=True)
        source = (stage / common_model).read_text(encoding="utf-8")
        destination.write_text(_coplanar_control_model(source), encoding="utf-8")


def set_variant(stage, originals, target, variant):
    for relative, source in originals.items():
        path = stage / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")

    renderer_paths = [Path(module) / PACKAGE / "client/renderers/ScreenBlockEntityRenderer.java" for module in MODULES]
    if target != NF26_TARGET and variant == "plain":
        # Plain is the common broken baseline: no depth protection and the old
        # coplanar backing, so the far-distance oracle must reproduce corruption.
        restore_common_control_models(stage)
    if target == NF26_TARGET and variant == "plain":
        # NF26 changed renderer topology while fixing the bug. Adapt its hash-locked
        # pre-fix renderer to the same public "plain" semantic instead of exposing
        # target-specific diagnostic variants in the required suite.
        restore_nf26_control_models(stage)
        verify_nf26_control(stage)
        for relative, fixture_name in NF26_CONTROL_FILES.items():
            (stage / relative).write_bytes((stage / NF26_CONTROL / fixture_name).read_bytes())
        instrument_nf26_visual_events(stage)
        renderer = stage / "neoforge-26.1.2" / PACKAGE / "client/renderers/ScreenBlockEntityRenderer.java"
        text = renderer.read_text(encoding="utf-8")
        if text.count("debugDraw(state);") != 1:
            raise RuntimeError("plain renderer instrumentation anchor changed")
        text = text.replace(
            "debugDraw(state);",
            "debugDraw(state);\n        com.nstut.simplyscreens.testing.visual.VisualClient.submittedTile(state.facing, state.width, state.height, "
            "state.blockPos.getX() + state.anchorOffsetX, state.blockPos.getY() + state.anchorOffsetY, "
            "state.blockPos.getZ() + state.anchorOffsetZ, state.blockPos.getX(), state.blockPos.getY(), state.blockPos.getZ());",
            1,
        )
        renderer.write_text(text, encoding="utf-8")
        return

    if variant in ("plain", "see-through"):
        see_through = variant == "see-through"
        for relative in renderer_paths:
            path = stage / relative
            source = path.read_text(encoding="utf-8")
            module = relative.parts[0]
            if module == "common-1.21.1":
                replacement = "RenderType.textSeeThrough(" if see_through else "RenderType.text("
                source = source.replace("ScreenRenderTypes.textPolygonOffset(", replacement)
            elif module == "neoforge-26.1.2":
                replacement = "RenderTypes.textSeeThrough(" if see_through else "RenderTypes.text("
                source = source.replace("ScreenRenderTypes.textPolygonOffset(", replacement)
            else:
                replacement = ".textSeeThrough(" if see_through else ".text("
                source = source.replace(".textPolygonOffset(", replacement)
            path.write_text(source, encoding="utf-8")


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


def remove_runtime_when_quiet(runtime, timeout=20.0, quiet_period=0.75):
    """Remove a stopped Minecraft runtime after late child/file-system activity settles."""
    deadline = time.monotonic() + timeout
    quiet_since = None
    while True:
        if runtime.exists():
            quiet_since = None
            try:
                shutil.rmtree(runtime)
            except FileNotFoundError:
                pass
            except OSError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.1)
                continue
        else:
            now = time.monotonic()
            if quiet_since is None:
                quiet_since = now
            elif now - quiet_since >= quiet_period:
                return
        if time.monotonic() >= deadline:
            raise RuntimeError(f"runtime did not remain removed: {runtime}")
        time.sleep(0.1)


def run_variant(stage, target, variant, directory, probe=False):
    from PIL import Image
    from visual_pixels import measure
    directory.mkdir(parents=True)
    Image.new("RGB", (32,32), (240,24,240)).save(directory / "fixture.png")
    # Fresh runtime only; compiled outputs/Gradle caches are reused between variants.
    runtime = (stage / target / "run/live-join").resolve()
    if not runtime.is_relative_to(stage.resolve()):
        raise RuntimeError("unsafe runtime cleanup")
    remove_runtime_when_quiet(runtime)
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
                count = sample_count(case)
                atomic_json(directory / "request.json", case)
                wait_until(lambda: (directory / f'{case["id"]}-{count-1:02d}.json').exists(), directory, server, client, 240)
                frames = []
                for index in range(count):
                    stem = f'{case["id"]}-{index:02d}'
                    frame = json.loads((directory / (stem+".json")).read_text())
                    if any(frame.get(k) != v for k,v in case.items()) or frame["sample"] != index or frame["submissions"] <= 0:
                        raise RuntimeError("frame belongs to a different/incomplete scenario")
                    if case.get("reload") and frame.get("reloaded") != (index >= SAMPLES):
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
        mutable = [Path(m) / PACKAGE / "client/renderers/ScreenBlockEntityRenderer.java" for m in MODULES]
        mutable += list(NF26_CONTROL_FILES.keys())[1:]
        mutable += list(NF26_WORLD_MODELS)
        mutable += list(COMMON_WORLD_MODEL_PATHS)
        originals = {relative: (stage / relative).read_text(encoding="utf-8") for relative in mutable}
        # Fixed viewport is also passed to the launcher (options alone do not resize the window).
        for target_name in TARGETS:
            build = stage / target_name / "build.gradle"
            if target_name == "neoforge-26.1.2":
                replace_once(build, "programArguments.addAll '--quickPlayMultiplayer', '127.0.0.1:25575'", "programArguments.addAll '--quickPlayMultiplayer', '127.0.0.1:25575', '--width', '1920', '--height', '1080'")
            else:
                replace_once(build, 'programArgs "--quickPlayMultiplayer", "127.0.0.1:25575"', 'programArgs "--quickPlayMultiplayer", "127.0.0.1:25575", "--width", "1920", "--height", "1080"')
        if compile_only:
            for variant in variants(target):
                set_variant(stage, originals, target, variant)
                subprocess.run(live.command(stage, f":{target}:classes"), cwd=stage, check=True)
                receipt["variants"][variant] = "compiled"
            receipt["status"] = "compile-only"
        else:
            for variant in variants(target):
                set_variant(stage, originals, target, variant)
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
        expected_variants = {v:"pass" for v in variants(r["target"])}
        if r.get("variants") != expected_variants:
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
