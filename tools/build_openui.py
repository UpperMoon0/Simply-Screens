#!/usr/bin/env python3
"""Build the pinned OpenUI artifacts once, with narrow retries for transient repository failures."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
TARGETS = (
    "fabric-1.20.1",
    "forge-1.20.1",
    "fabric-1.21.1",
    "neoforge-1.21.1",
    "neoforge-26.1.2",
)
TASKS = tuple(f":{target}:publishToMavenLocal" for target in TARGETS)
TRANSIENT_MARKERS = (
    "received status code 429",
    "received status code 500",
    "received status code 502",
    "received status code 503",
    "received status code 504",
    "connection reset",
    "connection timed out",
    "read timed out",
    "remote host terminated the handshake",
    "temporary failure in name resolution",
)


def property_value(path: Path, key: str) -> str:
    prefix = key + "="
    for raw in path.read_text(encoding="utf-8").splitlines():
        compact = raw.replace(" ", "")
        if compact.startswith(prefix):
            value = compact[len(prefix):].strip()
            if value:
                return value
    raise RuntimeError(f"missing {key} in {path}")


def required_version() -> str:
    return property_value(ROOT / "gradle.properties", "openui_version")


def artifact_paths(version: str, maven_root: Path) -> tuple[Path, ...]:
    return tuple(
        maven_root / "com" / "nstut" / f"openui-mc-{target}" / version
        / f"openui-mc-{target}-{version}.jar"
        for target in TARGETS
    )


def verify_artifacts(version: str, maven_root: Path) -> None:
    missing = [path for path in artifact_paths(version, maven_root) if not path.is_file()]
    if missing:
        formatted = "\n".join(f"  - {path}" for path in missing)
        raise RuntimeError(f"OpenUI Maven-local cache is incomplete:\n{formatted}")


def gradle_command(project: Path) -> list[str]:
    wrapper = project / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise RuntimeError(f"OpenUI Gradle wrapper missing: {wrapper}")
    return [
        str(wrapper),
        *TASKS,
        "--no-daemon",
        "--console=plain",
        "--max-workers=4",
    ]


def run_attempt(command: list[str], project: Path) -> tuple[int, str]:
    process = subprocess.Popen(
        command,
        cwd=project,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    assert process.stdout is not None
    lines: list[str] = []
    for line in process.stdout:
        print(line, end="", flush=True)
        lines.append(line)
    return process.wait(), "".join(lines)


def transient_failure(output: str) -> bool:
    lowered = output.lower()
    return any(marker in lowered for marker in TRANSIENT_MARKERS)


def build(project: Path, attempts: int, retry_delay: float) -> None:
    required = required_version()
    actual = property_value(project / "gradle.properties", "mod_version")
    if actual != required:
        raise RuntimeError(
            f"OpenUI version mismatch: Simply Screens requires {required!r}, "
            f"but {project} publishes {actual!r}"
        )

    command = gradle_command(project)
    for attempt in range(1, attempts + 1):
        print(f"OpenUI publish attempt {attempt}/{attempts}", flush=True)
        code, output = run_attempt(command, project)
        if code == 0:
            return
        if attempt == attempts or not transient_failure(output):
            raise subprocess.CalledProcessError(code, command)
        print("Transient dependency repository failure detected; retrying the publish invocation.", flush=True)
        time.sleep(retry_delay * attempt)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, default=ROOT / "OpenUI-MC")
    parser.add_argument("--maven-root", type=Path, default=Path.home() / ".m2" / "repository")
    parser.add_argument("--attempts", type=int, default=3)
    parser.add_argument("--retry-delay", type=float, default=5.0)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if args.attempts < 1:
        parser.error("--attempts must be at least 1")

    version = required_version()
    if not args.verify_only:
        build(args.project.resolve(), args.attempts, args.retry_delay)
    verify_artifacts(version, args.maven_root.expanduser().resolve())
    print(f"Verified all {len(TARGETS)} OpenUI {version} Maven-local artifacts.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"OpenUI dependency setup failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
