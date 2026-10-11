#!/usr/bin/env python3
"""Keep required runtime dependencies aligned with release metadata and artifacts."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PACKETWEAVE_SLUG = "packetweave"
PACKETWEAVE_PROJECT_ID = 1735096
TARGETS = ("fabric-1.20.1", "forge-1.20.1", "fabric-1.21.1", "neoforge-1.21.1", "neoforge-26.1.2")


def required_version(root: Path = ROOT) -> str:
    matches = re.findall(r"^packetweave_version\s*=\s*(\S+)\s*$", (root / "gradle.properties").read_text(), re.M)
    if len(matches) != 1 or not re.fullmatch(r"\d+\.\d+\.\d+", matches[0]):
        raise ValueError("Expected one numeric packetweave_version in gradle.properties")
    return matches[0]


def curseforge_relations(loader: str) -> dict:
    if loader not in {"fabric", "forge", "neoforge"}:
        raise ValueError(f"Unsupported loader: {loader}")
    projects = [{"slug": "architectury-api", "type": "requiredDependency"},
                {"slug": "openui-mc", "type": "requiredDependency"},
                {"slug": PACKETWEAVE_SLUG, "projectID": PACKETWEAVE_PROJECT_ID, "type": "requiredDependency"}]
    if loader == "fabric":
        projects.append({"slug": "fabric-api", "type": "requiredDependency"})
    # CurseForge relations do not express minimum versions; loader metadata does.
    return {"projects": projects}


def verify_curseforge_metadata(metadata: dict) -> None:
    """Require the exact project relation before the Upload API resolves it.

    The Upload API supports projectID for an exact project match. Its
    /api/game/dependencies endpoint is not a catalog of mod projects.
    https://support.curseforge.com/support/solutions/articles/9000197321
    """
    projects = metadata.get("relations", {}).get("projects", [])
    if any("projectID" in item and (type(item["projectID"]) is not int or item["projectID"] <= 0)
           for item in projects):
        raise ValueError("CurseForge projectID must be a positive JSON integer; publication is blocked")
    matches = [item for item in projects
               if item.get("slug") == PACKETWEAVE_SLUG or str(item.get("projectID")) == str(PACKETWEAVE_PROJECT_ID)]
    expected = {"slug": PACKETWEAVE_SLUG, "projectID": PACKETWEAVE_PROJECT_ID,
                "type": "requiredDependency"}
    if matches != [expected]:
        raise ValueError("CurseForge metadata must require PacketWeave's exact project ID and slug; publication is blocked")


def verify_release(release: dict, version: str) -> None:
    if release.get("tag_name") != f"v{version}" or release.get("draft") is not False or release.get("prerelease") is not False:
        raise ValueError(f"PacketWeave v{version} must be a published stable release before Screens publication")
    expected = {f"packetweave-{target}-{version}.jar" for target in TARGETS}
    uploaded = {asset.get("name") for asset in release.get("assets", [])
                if asset.get("state") == "uploaded" and isinstance(asset.get("size"), int) and asset["size"] > 0}
    missing = expected - uploaded
    if missing:
        raise ValueError(f"PacketWeave release lacks required native artifacts: {sorted(missing)}")


def check_published_release(version: str) -> None:
    url = f"https://api.github.com/repos/UpperMoon0/PacketWeave/releases/tags/v{version}"
    request = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "Simply-Screens-release"})
    with urllib.request.urlopen(request, timeout=30) as response:
        release = json.load(response)
    verify_release(release, version)
    print(f"PacketWeave v{version}: all five required native release artifacts are published")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    relations = commands.add_parser("relations")
    relations.add_argument("--loader", required=True)
    commands.add_parser("verify-release")
    commands.add_parser("verify-curseforge-metadata")
    args = parser.parse_args()
    if args.command == "relations":
        print(json.dumps(curseforge_relations(args.loader), separators=(",", ":")))
    elif args.command == "verify-curseforge-metadata":
        verify_curseforge_metadata(json.load(sys.stdin))
        print("CurseForge upload metadata requires exact PacketWeave project 1735096")
    else:
        check_published_release(required_version())


if __name__ == "__main__":
    main()
