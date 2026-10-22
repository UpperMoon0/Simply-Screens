#!/usr/bin/env python3
"""Reject incomplete, ambiguous or foreign artifacts before release publication."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile
from release_dependencies import TARGETS, REQUESTED_TARGETS

ALL_TARGETS = TARGETS + REQUESTED_TARGETS
ROOT = Path(__file__).resolve().parents[1]


def collect(source: Path, output: Path, version: str) -> dict:
    if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._+-]*", version):
        raise ValueError("Invalid release version")
    expected = {f"simply_screens-{target}-{version}.jar": target for target in ALL_TARGETS}
    jars = list(source.rglob("*.jar"))
    if len(jars) != len(expected) or {jar.name for jar in jars} != set(expected):
        raise ValueError("Expected exactly one production JAR for each of the ten release targets")
    entries = []
    for jar in sorted(jars):
        if jar.is_symlink() or not jar.is_file():
            raise ValueError("Release artifact must be a regular file")
        with zipfile.ZipFile(jar) as archive:
            if not archive.namelist() or archive.testzip() is not None:
                raise ValueError("Empty or corrupt release archive")
        data = jar.read_bytes()
        entries.append({"target": expected[jar.name], "file": jar.name, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)})
    # No partial output on validation errors; never mix a stale bundle into this release.
    if output.exists() and any(output.iterdir()):
        raise ValueError("Release output must be empty")
    output.mkdir(parents=True, exist_ok=True)
    for jar in jars:
        shutil.copyfile(jar, output / jar.name)
    manifest = {"version": version, "artifacts": entries}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (output / "SHA256SUMS").write_text("".join(f'{entry["sha256"]}  {entry["file"]}\n' for entry in entries))
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    matches = re.findall(r"^mod_version\s*=\s*(\S+)\s*$", (ROOT / "gradle.properties").read_text(), re.M)
    if len(matches) != 1:
        raise ValueError("Expected exactly one mod_version")
    collect(args.source, args.output, matches[0])
