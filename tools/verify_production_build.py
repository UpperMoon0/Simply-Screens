#!/usr/bin/env python3
"""Build and inspect one production target in an isolated Gradle project graph."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid

from build_openui import run_attempt, TRANSIENT_MARKERS
from visual_cases import TARGETS
from visual_test import ROOT, checkout_lock, scope_gradle, snapshot


def gradle_command(stage: Path, target: str) -> list[str]:
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    return [wrapper, f":{target}:build", "--no-daemon", "--console=plain"]


def retryable_build_failure(output: str) -> bool:
    lowered = output.lower()
    # Explicit product/test failures override incidental network-error text in
    # a test's output, exception message, or earlier successful download.
    deterministic = (
        "there were failing tests", "assertionerror", "assertion failed",
        "compilation failed", "cannot find symbol", "invalid mod metadata",
        "invalid simply screens production jar", "modloadingexception",
        "missing or unsupported mandatory dependencies",
    )
    if any(marker in lowered for marker in deterministic):
        return False
    if re.search(r"(?:> task|execution failed for task)[^\n]*:test(?:\s|['\"])", lowered):
        return False
    if re.search(r"> task[^\n]*:(?:processresources|generatemodmetadata)\s+failed", lowered):
        return False
    if re.search(r"(?m)^.*\.java:\d+:\s*error:", lowered):
        return False
    # In Gradle output use the final failure explanation, not a recovered
    # repository hiccup printed earlier in an otherwise successful phase.
    failure = lowered.rsplit("* what went wrong:", 1)[-1].split("* try:", 1)[0]
    dependency_failure = any(marker in failure for marker in (
        "could not resolve", "could not download", "could not get resource",
        "downloadassets", "downloading from https://",
    ))
    return dependency_failure and any(marker in failure for marker in TRANSIENT_MARKERS)


def verify_production_build(root: Path, target: str, attempts: int = 3) -> None:
    run_id = uuid.uuid4().hex
    stage = root / "build/screen-production-work" / target / run_id
    succeeded = False
    try:
        snapshot(root, stage)
        # Scope before Gradle starts. A task path alone does not stop Gradle from
        # configuring unrelated included projects (and their Loom dependencies).
        scope_gradle(stage, target)
        command = gradle_command(stage, target)
        for attempt in range(1, attempts + 1):
            code, output = run_attempt(command, stage)
            if code == 0:
                break
            if attempt == attempts or not retryable_build_failure(output):
                raise subprocess.CalledProcessError(code, command, output=output)
            print(
                f"Scoped Gradle dependency download/resolution failed transiently; "
                f"retrying ({attempt}/{attempts}).",
                flush=True,
            )
            time.sleep(attempt * 5)

        subprocess.run(
            [sys.executable, str(root / "tools/assert_production_jar.py"),
             "--module", str(stage / target)],
            cwd=root,
            check=True,
        )
        succeeded = True
    finally:
        if succeeded:
            shutil.rmtree(stage, ignore_errors=True)
        else:
            print(f"Failed scoped production workspace preserved at: {stage}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True, choices=TARGETS)
    parser.add_argument("--attempts", type=int, default=3)
    args = parser.parse_args()
    if args.attempts < 1:
        parser.error("--attempts must be at least 1")
    with checkout_lock(ROOT):
        verify_production_build(ROOT, args.target, args.attempts)


if __name__ == "__main__":
    main()
