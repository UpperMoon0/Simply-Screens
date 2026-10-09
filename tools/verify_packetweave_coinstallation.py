#!/usr/bin/env python3
"""Resolve real production jars together using pinned SecureJarHandler 3.0.8.

The negative control compiles the pinned, unmodified core twice into separate
module jars. This is a module-resolution regression, not a Minecraft launch.
The live-join harness separately installs the standalone native mod on both sides.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

from assert_production_jar import packetweave_dependency_errors, obsolete_entries

ROOT = Path(__file__).resolve().parents[1]
DEPENDENCIES = (
    ("https://maven.neoforged.net/releases/cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar",
     "945c63d6deafc821616b0380c23867d9b8c2852438f8a6df72732ab933fc587d"),
    ("https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.3/asm-9.3.jar",
     "1263369b59e29c943918de11d6d6152e2ec6085ce63e5710516f8c67d368e4bc"),
    ("https://repo.maven.apache.org/maven2/org/ow2/asm/asm-tree/9.3/asm-tree-9.3.jar",
     "ae629c2609f39681ef8d140a42a23800464a94f2d23e36d8f25cd10d5e4caff4"),
    ("https://repo.maven.apache.org/maven2/org/ow2/asm/asm-commons/9.3/asm-commons-9.3.jar",
     "a347c24732db2aead106b6e5996a015b06a3ef86e790a4f75b61761f0d2f7f39"),
)


def java_command() -> list[str]:
    home = os.environ.get("JAVA_HOME_25_X64") or os.environ.get("JAVA_HOME")
    return [str(Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if home else "java"]


def compile_java(sources: list[Path], destination: Path, classpath: str | None = None) -> None:
    if not sources:
        raise RuntimeError("No Java sources were found; initialize the pinned PacketWeave submodule")
    destination.mkdir(parents=True, exist_ok=True)
    # These short-lived fixtures run on this JVM only; production release levels
    # remain the native Gradle builds' responsibility. Some compact JDK installs
    # expose jdk.compiler but omit the ct.sym required by --release.
    command = [*java_command(), "com.sun.tools.javac.Main", "-encoding", "UTF-8", "-d", str(destination)]
    if classpath:
        command += ["-classpath", classpath]
    subprocess.run([*command, *(str(source) for source in sources)], check=True)


def core_jar(classes: Path, output: Path, module_name: str, license_file: Path) -> None:
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", f"Manifest-Version: 1.0\r\nAutomatic-Module-Name: {module_name}\r\n\r\n")
        archive.write(license_file, "META-INF/LICENSE-PacketWeave")
        for path in sorted(classes.rglob("*.class")):
            archive.write(path, path.relative_to(classes).as_posix())


def dependency_classpath(cache: Path) -> str:
    cache.mkdir(parents=True, exist_ok=True)
    jars = []
    for url, expected_hash in DEPENDENCIES:
        path = cache / url.rsplit("/", 1)[1]
        if not path.is_file():
            temporary = path.with_suffix(".download")
            subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--connect-timeout", "20",
                            "--max-time", "120", "--output", str(temporary), url], check=True)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected_hash:
                raise RuntimeError(f"Checksum mismatch for downloaded dependency: {url}")
            temporary.replace(path)
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected_hash:
            raise RuntimeError(f"Checksum mismatch for cached dependency: {path}")
        jars.append(str(path.resolve()))
    return os.pathsep.join(jars)


def verify(screens: Path, standalone: Path, cache: Path, root: Path = ROOT) -> None:
    missing, forbidden = packetweave_dependency_errors(screens), obsolete_entries(screens)
    if missing or forbidden:
        raise RuntimeError(f"Invalid Screens production jar: missing={missing}, forbidden={forbidden}")
    with zipfile.ZipFile(standalone) as archive:
        if "com/nstut/packetweave/api/TransferRegistry.class" not in archive.namelist():
            raise RuntimeError("Standalone jar does not contain PacketWeave's public API")
    classpath = dependency_classpath(cache)
    with tempfile.TemporaryDirectory(prefix="packetweave-coinstall-") as temporary:
        work = Path(temporary)
        core = root / "vendor/PacketWeave/core/src/main/java"
        classes = work / "core"
        compile_java(sorted(core.rglob("*.java")), classes)
        first, second = work / "packetweave.jar", work / "simply_screens_duplicate.jar"
        license_file = root / "vendor/PacketWeave/LICENSE"
        core_jar(classes, first, "packetweave", license_file)
        core_jar(classes, second, "simply_screens_duplicate", license_file)
        harness = work / "harness"
        compile_java([root / "tools/PacketWeaveCoinstallation.java"], harness, classpath)
        subprocess.run([*java_command(), "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                        "-classpath", os.pathsep.join((str(harness), classpath)),
                        "PacketWeaveCoinstallation", str(standalone.resolve()), str(screens.resolve()),
                        str(first), str(second)], check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--screens-jar", type=Path, required=True)
    parser.add_argument("--packetweave-jar", type=Path, required=True)
    parser.add_argument("--dependency-cache", type=Path, default=ROOT / ".gradle/packetweave-coinstallation")
    args = parser.parse_args()
    verify(args.screens_jar, args.packetweave_jar, args.dependency_cache)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
