#!/usr/bin/env python3
"""Keep required runtime dependencies aligned with release metadata and artifacts."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PACKETWEAVE_SLUG = "packetweave"
PACKETWEAVE_PROJECT_ID = "1735096"
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


def verify_curseforge_identity(dependencies: list) -> None:
    """Require the official Upload API catalog to confirm the recorded identity."""
    matches = [item for item in dependencies
               if item.get("slug") == PACKETWEAVE_SLUG or str(item.get("id")) == PACKETWEAVE_PROJECT_ID]
    if len(matches) != 1 or (matches[0].get("slug"), str(matches[0].get("id"))) != (PACKETWEAVE_SLUG, PACKETWEAVE_PROJECT_ID):
        raise ValueError("CurseForge did not confirm PacketWeave's exact project ID and slug; publication is blocked")


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
    identity = commands.add_parser("verify-curseforge-identity")
    identity.add_argument("--catalog", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "relations":
        print(json.dumps(curseforge_relations(args.loader), separators=(",", ":")))
    elif args.command == "verify-curseforge-identity":
        verify_curseforge_identity(json.loads(args.catalog.read_text()))
        print("Official CurseForge catalog confirms PacketWeave project ID and slug")
    else:
        check_published_release(required_version())


if __name__ == "__main__":
    main()
