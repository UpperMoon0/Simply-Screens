#!/usr/bin/env python3
"""Reuse the calibrated framebuffer oracle on isolated native 26.2 builds.

All instrumentation stays in a disposable source snapshot. A diagnostic probe
or successful compilation never emits a passing full visual-gate receipt.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import subprocess
import time
import uuid

import requested_live_join as native
import requested_visual_26 as adapter
import visual_test as visual
from visual_cases import variants

TARGETS=('fabric-26.2','neoforge-26.2')

def dependency_paths(root,target):
    paths={
        'packetweave':root.parent/'PacketWeave/targets'/target/'build/libs'/f'packetweave-{target}-0.1.4.jar',
        'openui':root.parent/'OpenUI-MC'/target/'build/libs'/f'openui-mc-{target}-0.0.15.jar',
    }
    for name,path in paths.items():
        if not path.is_file():raise RuntimeError(f'Build the pinned native {name} dependency first: {path}')
    return {key:path.resolve() for key,path in paths.items()}

def command(stage,target,task,dependencies,packetweave_source):
    return [*native.command(stage/target,task),'-p',str(stage/target),
            '-PopenuiJar='+str(dependencies['openui']),
            '-PpacketweaveJar='+str(dependencies['packetweave']),
            '-PpacketweaveSource='+str(packetweave_source)]

def prepare_snapshot(stage,target):
    adapter.instrument(stage)
    visual.replace_once(stage/target/'build.gradle',
        "'--quickPlayMultiplayer','127.0.0.1:25575'\n",
        "'--quickPlayMultiplayer','127.0.0.1:25575','--width','1920','--height','1080'\n")

def run(root,target,compile_only=False,probe=False):
    root=root.resolve()
    if target not in TARGETS:raise ValueError('Unsupported native visual target: '+target)
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip())
    run_id=uuid.uuid4().hex
    evidence=root/'build/requested-visual-evidence'/target/run_id
    stage=root/'build/requested-visual-work'/target/run_id
    evidence.mkdir(parents=True)
    receipt=dict(head=head,dirty=dirty,target=target,run_id=run_id,status='running',variants={},
                 scope='native development framebuffer oracle with calibrated negative controls',
                 notEstablished=['production-jar launch','other graphics backends','shader compatibility'])
    started=time.monotonic()
    try:
        dependencies=dependency_paths(root,target)
        if visual.live.native_version(dependencies['packetweave'],target)!='0.1.4':
            raise RuntimeError('Wrong native PacketWeave version')
        receipt['dependencies']={key:dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(),file=path.name)
                                 for key,path in dependencies.items()}
        receipt['source_sha256']=visual.snapshot(root,stage)
        prepare_snapshot(stage,target)
        originals={path:(stage/path).read_text() for path in adapter.mutable_paths()}
        packetweave_source=(root.parent/'PacketWeave/core/src/main/java').resolve()
        native_command=lambda snapshot,name,task:command(snapshot,name,task,dependencies,packetweave_source)
        calibration_failure=None
        for variant in variants(target):
            adapter.set_variant(stage,originals,variant)
            if compile_only:
                subprocess.run(native_command(stage,target,'classes'),cwd=stage,check=True)
                receipt['variants'][variant]='compiled'
                continue
            outcome='diagnostic' if probe else 'pass'
            try:
                visual.run_variant(stage,target,variant,evidence/variant,dependencies['packetweave'],probe,
                    native_commands=native_command,dependency_setup=lambda *_:None,
                    runtime_setup=lambda _:native.verify_loader_warning_visibility(stage/target))
            except ValueError as error:
                # Only this completed historical-control verdict may continue to
                # the independent occlusion control. Startup, cleanup, incomplete
                # manifests, other pixel failures and all diagnostics still abort.
                if probe or variant!='plain' or str(error)!='INCONCLUSIVE: plain renderer did not reproduce z-fighting':
                    raise
                calibration_failure=error
                outcome='inconclusive'
            graphics=json.loads((evidence/variant/'graphics.json').read_text())
            if 'graphics' in receipt and receipt['graphics']!=graphics:
                raise RuntimeError('Negative controls used a different graphics backend')
            receipt['graphics']=graphics
            receipt['variants'][variant]=outcome
        if calibration_failure is not None:raise calibration_failure
        receipt['status']='compile-only' if compile_only else 'probe-only' if probe else 'pass'
    except BaseException as error:
        receipt['status']='fail';receipt['error']=str(error)
        raise
    finally:
        receipt['elapsed_seconds']=time.monotonic()-started
        visual.atomic_json(evidence/'result.json',receipt)
        print('Native visual evidence: '+str(evidence),flush=True)
    return receipt

def verify_receipt(receipt,head):
    if receipt.get('head')!=head or receipt.get('dirty') is not False or receipt.get('status')!='pass':
        raise ValueError('Stale, dirty, failed or diagnostic-only native visual receipt')
    if receipt.get('target') not in TARGETS:
        raise ValueError('Wrong native visual target')
    if receipt.get('variants')!={variant:'pass' for variant in variants(receipt['target'])}:
        raise ValueError('Missing calibrated negative controls')
    if not re.fullmatch(r'[a-f0-9]{64}',receipt.get('source_sha256','')):
        raise ValueError('Missing exact source snapshot digest')
    dependencies=receipt.get('dependencies',{})
    if set(dependencies)!={'packetweave','openui'} or any(not re.fullmatch(r'[a-f0-9]{64}',item.get('sha256','')) for item in dependencies.values()):
        raise ValueError('Missing pinned native dependency artifact identities')
    if not all(receipt.get('graphics',{}).get(key) for key in ('graphicsVendor','graphicsRenderer','graphicsVersion')):
        raise ValueError('Missing actual graphics backend identity')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--target',choices=TARGETS)
    parser.add_argument('--verify-receipt',type=Path)
    parser.add_argument('--head')
    parser.add_argument('--compile-only',action='store_true')
    parser.add_argument('--probe',action='store_true',help='diagnostic subset, never a passing visual gate')
    args=parser.parse_args()
    if args.verify_receipt:
        if not args.head:parser.error('--head is required with --verify-receipt')
        verify_receipt(json.loads(args.verify_receipt.read_text()),args.head)
    else:
        if not args.target:parser.error('--target is required')
        root=Path(__file__).resolve().parents[1]
        with visual.checkout_lock(root):run(root,args.target,args.compile_only,args.probe)
