#!/usr/bin/env python3
"""Fail CI if a production Simply Screens jar contains obsolete mixin metadata."""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path


def production_jars(module_dir: Path) -> list[Path]:
    libs = module_dir / "build" / "libs"
    return sorted(
        jar
        for jar in libs.glob("simply_screens-*.jar")
        if not jar.name.endswith("-sources.jar")
        and not jar.name.endswith("-dev-shadow.jar")
    )


def obsolete_entries(jar: Path) -> list[str]:
    with zipfile.ZipFile(jar) as archive:
        entries = archive.namelist()
    return sorted(
        entry
        for entry in entries
        if Path(entry).name == "simplyscreens.mixins.json"
        or (
            Path(entry).name.lower().endswith("refmap.json")
            and ("simply_screens" in Path(entry).name.lower() or "simplyscreens" in Path(entry).name.lower())
        )
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--module", required=True, help="Gradle module containing build/libs")
    args = parser.parse_args()

    module_dir = Path(args.module)
    jars = production_jars(module_dir)
    if len(jars) != 1:
        found = ", ".join(str(jar) for jar in jars) or "none"
        print(f"Expected exactly one production jar for {args.module}; found: {found}", file=sys.stderr)
        return 1

    jar = jars[0]
    obsolete = obsolete_entries(jar)
    if obsolete:
        print(f"Obsolete Simply Screens mixin metadata found in {jar}:", file=sys.stderr)
        for entry in obsolete:
            print(f"  - {entry}", file=sys.stderr)
        return 1

    print(f"{jar}: no obsolete Simply Screens mixin config/refmap entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
