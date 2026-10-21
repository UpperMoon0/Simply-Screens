#!/usr/bin/env python3
"""Build once, then verify exact-source native prerequisites before shard use.

No cross-run build cache: packages come only from this workflow's target job.
Downloaded dependency caches never substitute for successful production checks.
"""
import argparse
import hashlib
import json
import platform
from pathlib import Path
import shutil
import subprocess
import tomllib
import zipfile

from assert_production_jar import loader_metadata
from build_openui import property_value

import live_join_test as live
import visual_test as visual
from verify_production_build import verify_production_build
from visual_cases import TARGETS

ROOT=Path(__file__).resolve().parents[1]


def toolchain():
    return dict(java=subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True).strip(),
                system=platform.system(),architecture=platform.machine())


def identity(root):
    if subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip():
        raise ValueError('native prerequisite requires clean sources')
    return dict(head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
                source_sha256=visual.snapshot(root),
                packetweave_source=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root/'vendor/PacketWeave',text=True).strip())


def verify_native_metadata(jar,target,root=ROOT):
    # Match the pinned target's complete manifest, not just a renamed filename
    # or the common mod ID shared by every Minecraft generation.
    project=root/'vendor/PacketWeave/targets'/target
    version=property_value(project/'gradle.properties','mod_version')
    if live.native_version(jar,target)!=version: raise ValueError('wrong native prerequisite version')
    metadata=loader_metadata(target)
    expected=(project/'src/main/resources'/metadata).read_text().replace('${mod_version}',version)
    with zipfile.ZipFile(jar) as archive: actual=archive.read(metadata).decode()
    parse=json.loads if metadata.endswith('.json') else tomllib.loads
    if parse(actual)!=parse(expected): raise ValueError('wrong native target/loader/dependency metadata')


def verify_compiler(report,target,jar):
    expected=17 if target.endswith('1.20.1') else 21 if target.endswith('1.21.1') else 25
    compiler=report.get('compiler',{})
    if (report.get('schema')!=1 or report.get('task')!=':compileJava'
            or report.get('execution')!='executed' or report.get('release')!=expected
            or compiler.get('language_version')!=expected
            or any(not isinstance(compiler.get(k),str) or not compiler[k]
                   for k in ('java_runtime_version','jvm_version','vendor'))):
        raise ValueError('missing or wrong resolved PacketWeave compiler/release provenance')
    with zipfile.ZipFile(jar) as archive:
        classes=[n for n in archive.namelist() if n.startswith('com/nstut/packetweave/') and n.endswith('.class')]
        if not classes: raise ValueError('native prerequisite has no compiled classes')
        for name in classes:
            data=archive.read(name)
            if len(data)<8 or data[:4]!=bytes.fromhex('cafebabe') or int.from_bytes(data[6:8],'big')!=expected+44:
                raise ValueError('native prerequisite class bytecode does not match target Java release')


def validate(receipt, expected, target, packet_toolchain, build_toolchain, package):
    if receipt.get('schema')!=1 or receipt.get('status')!='pass' or receipt.get('target')!=target:
        raise ValueError('failed or wrong-target native prerequisite')
    if receipt.get('identity')!=expected:
        raise ValueError('stale native prerequisite source or dependency')
    if receipt.get('packet_launcher')!=packet_toolchain or receipt.get('build_launcher')!=build_toolchain:
        raise ValueError('native prerequisite toolchain mismatch')
    name=receipt.get('jar','')
    if Path(name).name!=name or not name.startswith(f'packetweave-{target}-') or not name.endswith('.jar'):
        raise ValueError('wrong native prerequisite filename')
    path=package/name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=receipt.get('sha256'):
        raise ValueError('missing or corrupted native prerequisite')
    # The existing native manifest checks still run again during installation.
    verify_native_metadata(path,target)
    verify_compiler(receipt.get('packet_compiler',{}),target,path)
    return path


def create(root,target,package,packet_toolchain,compiler_file=None):
    package.mkdir(parents=True,exist_ok=True)
    receipt_path=package/'prerequisite.json'
    receipt_path.unlink(missing_ok=True)
    before=identity(root)
    verify_production_build(root,target)
    if identity(root)!=before: raise ValueError('sources changed during production verification')
    jar=live.packetweave_jar(root,target)
    verify_native_metadata(jar,target,root)
    compiler=json.loads((compiler_file or root/'build/packet-compiler.json').read_text())
    verify_compiler(compiler,target,jar)
    receipt=dict(schema=1,status='pass',target=target,identity=before,packet_launcher=packet_toolchain,
                 packet_compiler=compiler,build_launcher=toolchain(),jar=jar.name,sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
    shutil.copy2(jar,package/jar.name)
    visual.atomic_json(receipt_path,receipt)


def consume(root,target,package,packet_toolchain):
    receipt=json.loads((package/'prerequisite.json').read_text())
    jar=validate(receipt,identity(root),target,packet_toolchain,toolchain(),package)
    libs=root/'vendor/PacketWeave/targets'/target/'build/libs'
    libs.mkdir(parents=True,exist_ok=True)
    if list(libs.glob('*.jar')): raise ValueError('refusing ambiguous native prerequisite destination')
    shutil.copy2(jar,libs/jar.name)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=('toolchain','create','consume'))
    p.add_argument('--target',choices=TARGETS)
    p.add_argument('--package',type=Path)
    p.add_argument('--toolchain-file',type=Path,required=True)
    a=p.parse_args()
    if a.mode=='toolchain':
        a.toolchain_file.parent.mkdir(parents=True,exist_ok=True)
        visual.atomic_json(a.toolchain_file,toolchain())
        return
    if not a.target or not a.package:p.error('--target and --package required')
    packet=json.loads(a.toolchain_file.read_text())
    with visual.checkout_lock(ROOT):
        (create if a.mode=='create' else consume)(ROOT,a.target,a.package,packet)

if __name__=='__main__':main()
