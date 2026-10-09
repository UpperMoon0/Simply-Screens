"""Stage the standalone mod and verify real loader dependency rejections."""
from __future__ import annotations

import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
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


def assert_dependency_rejected(output: str, returncode: int, mode: str) -> None:
    lowered = output.lower()
    if returncode == 0 or "done (" in lowered:
        raise RuntimeError(f"{mode}: loader started despite the invalid PacketWeave dependency")
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
                try:
                    output, _ = process.communicate(timeout=timeout)
                except subprocess.TimeoutExpired:
                    live.stop_tree(process)
                    output, _ = process.communicate()
                    log.write_text(output, encoding="utf-8")
                    raise RuntimeError(f"{mode}: server failed to exit with an explicit PacketWeave dependency rejection")
                finally:
                    live.stop_tree(process)
                log.write_text(output, encoding="utf-8")
                assert_dependency_rejected(output, process.returncode, mode)
                print(f"{target}: PASS real loader rejected {mode} PacketWeave; log: {log}", flush=True)
        finally:
            invalid.unlink(missing_ok=True)
            for original, backup in saved:
                shutil.move(backup, original)
