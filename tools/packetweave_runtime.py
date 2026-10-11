"""Stage the standalone mod and verify real loader dependency rejections."""
from __future__ import annotations

import json
from pathlib import Path
import queue
import re
import shutil
import tempfile
import time
import tomllib
import zipfile

from assert_production_jar import loader_metadata


def native_version(jar: Path, target: str) -> str:
    with zipfile.ZipFile(jar) as archive:
        path = loader_metadata(target)
        required = {path, "com/nstut/packetweave/api/TransferRegistry.class"}
        if not required <= set(archive.namelist()):
            raise RuntimeError(f"Expected standalone {target} PacketWeave native mod contents in {jar}")
        text = archive.read(path).decode("utf-8")
    if path == "fabric.mod.json":
        metadata = json.loads(text)
        if metadata.get("id") != "packetweave":
            raise RuntimeError("Standalone Fabric jar must declare mod id packetweave")
        return str(metadata["version"])
    entries = [entry for entry in tomllib.loads(text).get("mods", []) if entry.get("modId") == "packetweave"]
    if len(entries) != 1:
        raise RuntimeError("Standalone native jar must declare one PacketWeave mod")
    return str(entries[0]["version"])


def incompatible_jar(source: Path, destination: Path, target: str) -> None:
    """Only change the actual native mod's version metadata for the negative control."""
    old_version = native_version(source, target)
    metadata_path = loader_metadata(target)
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w") as output:
        for info in original.infolist():
            data = original.read(info.filename)
            if info.filename == metadata_path:
                text = data.decode("utf-8")
                if metadata_path == "fabric.mod.json":
                    metadata = json.loads(text)
                    metadata["version"] = "0.1.0"
                    text = json.dumps(metadata)
                else:
                    text, changed = re.subn(r'(?m)^(\s*version\s*=\s*)[\"\']' + re.escape(old_version) + r'[\"\']',
                                            r'\g<1>"0.1.0"', text, count=1)
                    if changed != 1:
                        raise RuntimeError("Could not replace the native PacketWeave version for negative control")
                data = text.encode("utf-8")
            output.writestr(info, data)
    if native_version(destination, target) != "0.1.0":
        raise RuntimeError("Incompatible PacketWeave fixture still reports a compatible version")



def terminal_forge_mixin_rejection(output: str) -> bool:
    """Forge removes non-system mods after dependency validation fails, including
    their mixin resources. Recognize only this exact secondary bootstrap failure;
    assert_dependency_rejected still requires the bound PacketWeave diagnostic.
    """
    text=re.sub(r"\x1b\[[0-9;]*m|§.", "", output.lower())
    markers=("missing or unsupported mandatory dependencies:",
             'exception in thread "main" org.spongepowered.asm.launch.mixininitialisationerror: '
             'error initialising mixin config simplyscreens-color.mixins.json',
             "the specified resource 'simplyscreens-color.mixins.json' was invalid or could not be read")
    positions=[text.find(marker) for marker in markers]
    return all(position>=0 for position in positions) and positions==sorted(positions)


def assert_dependency_rejected(output: str, returncode: int | None, mode: str) -> None:
    lowered = output.lower()
    if returncode == 0 or "done (" in lowered:
        raise RuntimeError(f"{mode}: loader started despite the invalid PacketWeave dependency")
    fatal_server = "failed to start the minecraft server" in lowered
    fatal_bootstrap = terminal_forge_mixin_rejection(output)
    fatal_loader = ("net.neoforged.fml.modloadingexception" in lowered
                    or "net.minecraftforge.fml.loadingfailedexception" in lowered
                    or ("net.fabricmc.loader.impl.formattedexception" in lowered and "incompatible mods found" in lowered))
    if returncode is None and not (fatal_server or fatal_bootstrap):
        raise RuntimeError(f"{mode}: running launcher has not reported a fatal server-start failure")
    if returncode is not None and not (fatal_server or fatal_loader or fatal_bootstrap):
        raise RuntimeError(f"{mode}: process exited without a fatal loader rejection")
    # A generic crash, repository failure, or timeout cannot satisfy this test.
    diagnostic = False
    # Bind the actual version to the same dependency identity. A neighboring
    # missing mod, or the shared "Missing or unsupported" header, is not proof.
    lowered = re.sub(r"\x1b\[[0-9;]*m|§.", "", lowered)
    lines = lowered.splitlines()
    expected_actual = "[missing]" if mode == "missing" else "0.1.0"
    row = re.compile(r"mod id:\s*'packetweave',\s*requested by:\s*'simply_screens',\s*"
                     r"expected range:\s*'\[0\.1\.1,\)',\s*actual version:\s*'([^']+)'")
    for index, line in enumerate(lines):
        match = row.search(line)
        if match and match.group(1) == expected_actual:
            diagnostic = True
        if all(token in line for token in ("simply_screens", "requires", "packetweave", "0.1.1")):
            if mode == "missing" and "which is missing" in line:
                diagnostic = True
            if mode == "incompatible" and "0.1.0" in line and "version" in line:
                diagnostic = True
            following = lines[index + 1] if index + 1 < len(lines) else ""
            current = re.search(r"currently,?\s+packetweave\s+is\s+(not installed|0\.1\.0)(?![0-9.])", following)
            if current and current.group(1) == ("not installed" if mode == "missing" else "0.1.0"):
                diagnostic = True
    if not diagnostic:
        raise RuntimeError(f"{mode}: loader did not explicitly reject Screens' PacketWeave dependency; inspect preserved log")


def wait_for_dependency_rejection(process, mode: str, timeout: int, log: Path, prefix: str) -> bool:
    """Observe rejection before cleanup; a termination signal is never evidence.

    Forge/NeoForge development launchers may retain non-daemon threads after
    ServerModLoader throws and Minecraft Main logs a fatal startup failure, or
    Forge excludes invalid mods and their manifest-registered mixin resources.
    Accept that explicit terminal loader state, then clean up the launcher.
    """
    import live_join_test as live
    pump = live.OutputPump(process, prefix)
    deadline = time.monotonic() + timeout
    observed_code = None
    verified = False
    try:
        while time.monotonic() < deadline:
            output = "".join(pump.history)
            if "done (" in output.lower():
                raise RuntimeError(f"{mode}: server became ready despite the invalid PacketWeave dependency")
            code = process.poll()
            if code is not None and not pump.thread.is_alive():
                assert_dependency_rejected(output, code, mode)
                observed_code = code
                verified = True
                break
            if "failed to start the minecraft server" in output.lower() or terminal_forge_mixin_rejection(output):
                # Do not infer rejection from a fatal error for a different mod,
                # or from a diagnostic alone without completed startup failure.
                try:
                    assert_dependency_rejected(output, None, mode)
                except RuntimeError:
                    pass
                else:
                    verified = True
                    break
            try:
                pump.lines.get(timeout=max(0.01, min(1.0, deadline - time.monotonic())))
            except queue.Empty:
                pass
        if not verified:
            raise RuntimeError(f"{mode}: timed out without explicit terminal PacketWeave dependency rejection")
    finally:
        live.stop_tree(process)
        pump.thread.join(timeout=10)
        log.write_text("".join(pump.history), encoding="utf-8")
        if pump.thread.is_alive():
            raise RuntimeError(f"{mode}: launcher output did not fully drain after cleanup; rejection is unverified")
    # Include output drained during cleanup: a late ready marker must still fail.
    assert_dependency_rejected("".join(pump.history), observed_code, mode)
    return observed_code is None


def check_dependency_rejections(root: Path, target: str, standalone: Path, timeout: int) -> None:
    # Import lazily to keep the runtime utilities usable by both harnesses.
    import live_join_test as live
    module = root / target
    runtime = module / "run/live-join"
    mods = runtime / "server/mods"
    mods.mkdir(parents=True, exist_ok=True)
    evidence = runtime / "dependency-checks"
    evidence.mkdir(parents=True, exist_ok=True)
    # Keep any prior positive test copy recoverable while testing actual absence.
    with tempfile.TemporaryDirectory(prefix="packetweave-negative-") as temporary:
        stash = Path(temporary)
        saved = []
        for jar in mods.glob("packetweave-*.jar"):
            saved.append((jar, stash / jar.name))
            shutil.move(jar, stash / jar.name)
        invalid = mods / f"packetweave-{target}-0.1.0.jar"
        try:
            for mode in ("missing", "incompatible"):
                if mode == "incompatible":
                    incompatible_jar(standalone, invalid, target)
                log = evidence / f"{mode}-server.log"
                process = live.popen(live.command(root, f":{target}:runLiveJoinTestServer"), root)
                lingering = wait_for_dependency_rejection(process, mode, timeout, log, f"{target}/{mode}")
                cleanup = "; stopped lingering development launcher after fatal rejection" if lingering else ""
                print(f"{target}: PASS real loader rejected {mode} PacketWeave{cleanup}; log: {log}", flush=True)
        finally:
            invalid.unlink(missing_ok=True)
            for original, backup in saved:
                shutil.move(backup, original)
