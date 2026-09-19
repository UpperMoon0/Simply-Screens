#!/usr/bin/env python3
"""Build and inspect one production target in an isolated Gradle project graph."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

from visual_cases import TARGETS
from visual_test import ROOT, checkout_lock, scope_gradle, snapshot


def gradle_command(stage: Path, target: str) -> list[str]:
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    return [wrapper, f":{target}:build", "--no-daemon", "--console=plain"]


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
            result = subprocess.run(command, cwd=stage)
            if result.returncode == 0:
                break
            if attempt == attempts:
                raise subprocess.CalledProcessError(result.returncode, command)
            print(
                f"Scoped Gradle production build failed; retrying after transient "
                f"repository/build setup failure ({attempt}/{attempts}).",
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
