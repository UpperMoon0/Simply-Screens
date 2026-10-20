#!/usr/bin/env python3
"""Require a standalone PacketWeave dependency and production-only jar contents."""
from __future__ import annotations

import argparse
import io
import json
from pathlib import Path
import sys
import tomllib
import zipfile

MINIMUM_PACKETWEAVE = "0.1.1"
PACKETWEAVE_PACKAGES = ("com/nstut/packetweave/", "com/nstut/simplyscreens/internal/packetweave/")


def loader_metadata(target: str) -> str:
    return ("fabric.mod.json" if target.startswith("fabric-") else
            "META-INF/mods.toml" if target.startswith("forge-") else "META-INF/neoforge.mods.toml")


def packetweave_dependency_errors(jar: Path) -> list[str]:
    """Inspect expanded loader metadata, not source-template string matches."""
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        metadata = [name for name in ("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml") if name in names]
        if len(metadata) != 1:
            return ["expected exactly one native loader metadata file"]
        name = metadata[0]
        try:
            text = archive.read(name).decode("utf-8")
            if name == "fabric.mod.json":
                data = json.loads(text)
                errors = []
                if data.get("id") != "simply_screens":
                    errors.append("Fabric mod id must be simply_screens")
                if data.get("environment", "*") != "*":
                    errors.append("Screens must load on both sides")
                if data.get("depends", {}).get("packetweave") != ">=" + MINIMUM_PACKETWEAVE:
                    errors.append("Fabric requires depends.packetweave >=0.1.1")
                return errors
            data = tomllib.loads(text)
            dependencies = [entry for entry in data.get("dependencies", {}).get("simply_screens", [])
                            if entry.get("modId") == "packetweave"]
            if len(dependencies) != 1:
                return ["expected exactly one PacketWeave loader dependency"]
            dependency = dependencies[0]
            errors = []
            if dependency.get("versionRange") != "[" + MINIMUM_PACKETWEAVE + ",)":
                errors.append("PacketWeave versionRange must be [0.1.1,)")
            if dependency.get("side") != "BOTH":
                errors.append("PacketWeave must be required on BOTH sides")
            if name == "META-INF/mods.toml" and dependency.get("mandatory") is not True:
                errors.append("Forge PacketWeave dependency must be mandatory")
            if name == "META-INF/neoforge.mods.toml" and dependency.get("type") != "required":
                errors.append("NeoForge PacketWeave dependency must be required")
            return errors
        except (ValueError, UnicodeError, TypeError, AttributeError) as error:
            return [f"invalid {name}: {error}"]


def production_jars(module_dir: Path) -> list[Path]:
    return sorted(jar for jar in (module_dir / "build/libs").glob("simply_screens-*.jar")
                  if not jar.name.endswith(("-sources.jar", "-dev-shadow.jar")))


COLOR_MIXIN_CONFIG = "simplyscreens-color.mixins.json"
COLOR_REFMAP = "simplyscreens-color.refmap.json"
COLOR_MIXIN_CLASS = "com/nstut/simplyscreens/client/mixin/GameRendererMixin"


def valid_color_mixin(archive: zipfile.ZipFile) -> bool:
    """Allow only the new production client hook; obsolete/test mixins stay forbidden."""
    try:
        config = json.loads(archive.read(COLOR_MIXIN_CONFIG))
        if (config.get("package") != "com.nstut.simplyscreens.client.mixin"
                or config.get("client") != ["GameRendererMixin"]
                or config.get("mixins") or config.get("server") or config.get("plugin")
                or config.get("required") is not True
                or config.get("injectors", {}).get("defaultRequire") != 1
                or COLOR_MIXIN_CLASS + ".class" not in archive.namelist()):
            return False
        refmap = config.get("refmap")
        if refmap is None:
            return COLOR_REFMAP not in archive.namelist()  # Unobfuscated modern Minecraft.
        if refmap != COLOR_REFMAP: return False
        mappings = json.loads(archive.read(COLOR_REFMAP))
        allowed = {COLOR_MIXIN_CLASS, COLOR_MIXIN_CLASS.replace("/", ".")}
        if not mappings.get("mappings") or not set(mappings["mappings"]) <= allowed:
            return False
        return all(set(context) <= allowed for context in mappings.get("data", {}).values())
    except (KeyError, ValueError, TypeError, AttributeError):
        return False



def color_mixin_registration_errors(jar: Path) -> list[str]:
    """A valid but unregistered hook is as broken as an absent one in a release jar."""
    with zipfile.ZipFile(jar) as archive:
        if not valid_color_mixin(archive): return ['missing or invalid production color hook']
        names=set(archive.namelist())
        try:
            if 'fabric.mod.json' in names:
                configs=json.loads(archive.read('fabric.mod.json')).get('mixins',[])
                registered=COLOR_MIXIN_CONFIG in configs or any(
                    isinstance(item,dict) and item.get('config')==COLOR_MIXIN_CONFIG
                    and item.get('environment') in (None,'client') for item in configs)
            elif 'META-INF/neoforge.mods.toml' in names:
                registered=any(entry.get('config')==COLOR_MIXIN_CONFIG for entry in
                    tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode()).get('mixins',[]))
            elif 'META-INF/mods.toml' in names:
                # JAR manifest attributes can wrap at72bytes; continuation starts with a space.
                manifest=archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n','\n').replace('\n ','')
                registered=any(COLOR_MIXIN_CONFIG in line.split(':',1)[1].strip().split(',')
                    for line in manifest.splitlines() if line.startswith('MixinConfigs:'))
            else: registered=False
            return [] if registered else ['production color hook is not registered with its loader']
        except (KeyError,ValueError,UnicodeError,TypeError,AttributeError):
            return ['invalid production color hook registration metadata']


def _forbidden(archive: zipfile.ZipFile, prefix: str = "") -> list[str]:
    entries = []
    color_allowed = not prefix and valid_color_mixin(archive)
    for name in archive.namelist():
        if (Path(name).name == "simplyscreens.mixins.json"
                or (Path(name).name == COLOR_MIXIN_CONFIG and not color_allowed)
                or name.startswith("com/nstut/simplyscreens/testing/visual/")
                or name.startswith("com/nstut/simplyscreens/testing/playlist/")
                or (name.endswith(".class") and any("/" + package in "/" + name for package in PACKETWEAVE_PACKAGES))
                or (Path(name).name.lower().endswith("refmap.json")
                    and any(mod in Path(name).name.lower() for mod in ("simply_screens", "simplyscreens"))
                    and not (name == COLOR_REFMAP and color_allowed))):
            entries.append(prefix + name)
        if name.endswith(".jar"):
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                entries.extend(_forbidden(nested, prefix + name + "!"))
    return entries


def obsolete_entries(jar: Path) -> list[str]:
    with zipfile.ZipFile(jar) as archive:
        return sorted(_forbidden(archive))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--module", required=True, help="Gradle module containing build/libs")
    args = parser.parse_args()
    jars = production_jars(Path(args.module))
    if len(jars) != 1:
        print(f"Expected exactly one production jar for {args.module}; found: {jars}", file=sys.stderr)
        return 1
    jar = jars[0]
    problems = packetweave_dependency_errors(jar) + obsolete_entries(jar) + color_mixin_registration_errors(jar)
    from check_patchouli_jar import validate_archive
    try:
        validate_archive(jar, Path(args.module).name)
    except (ValueError, KeyError) as error:
        problems.append(f"Optional Patchouli guide: {error}")
    if problems:
        print(f"Invalid Simply Screens production jar {jar}:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1
    print(f"{jar}: required standalone PacketWeave >=0.1.1 on both sides; no embedded API, obsolete mixins, or visual drivers")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
