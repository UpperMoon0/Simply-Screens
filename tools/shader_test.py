#!/usr/bin/env python3
"""Real Iris/Oculus + Complementary source-color regression, with unmodified-world control."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

import visual_test as visual
import live_join_test as live
from visual_cases import TARGETS, FACES, sample_count
from shader_stack import load_lock, prepare, install_pack
import shader_pixels

ROOT = Path(__file__).resolve().parents[1]
VARIANTS = ('shader-off', 'original', 'fixed', 'bloom-off')

def scenarios():
    result=[]
    for face in FACES:
        for distance,angle,occluded in ((8,0,False),(32,60,False),(8,0,True)):
            result.append(dict(id=f'{face}-{distance}-{angle}-{occluded}',facing=face,size=8,
                               distance=distance,angle=angle,occluded=occluded))
    result.append(dict(id='NORTH-reload',facing='NORTH',size=8,distance=16,angle=0,occluded=False,reload=True))
    result.append(dict(id='NORTH-alpha',facing='NORTH',size=8,distance=16,angle=0,occluded=False,alpha=True))
    return result

def instrument(stage):
    visual.instrument(stage)
    # Loader END events are before shader finalization or have ambiguous mixin order.
    # Keep their frame-start hooks; take samples from our post-LevelRenderer hook instead.
    for target in TARGETS:
        for file in (stage/target/'src/main/java').rglob('*.java'):
            text=file.read_text()
            text=text.replace('VisualClient.afterRender(', 'VisualClient.beforeShaderFinalization(')
            file.write_text(text)
    for module in visual.MODULES:
        base=stage/module/visual.PACKAGE
        driver=base/'testing/visual/VisualClient.java'
        text=driver.read_text().replace('    public static void afterRender(Minecraft mc) {',
            '    public static void beforeShaderFinalization(Minecraft mc) {}\n\n    public static void afterRender(Minecraft mc) {')
        text=text.replace('        submissions++;',
            '        if (com.nstut.simplyscreens.client.compat.ShaderPackState.isRenderingShadowPass()) return;\n        submissions++;')
        text=text.replace('            frame.addProperty("sample", index);',
            '            frame.addProperty("sample", index);\n            frame.addProperty("shaderPackActive", com.nstut.simplyscreens.client.compat.ShaderPackState.shouldCapture());')
        driver.write_text(text)
        hook=base/'client/mixin/GameRendererMixin.java'
        text=hook.read_text()
        needle='ScreenColorRestore.finishWorld();'
        if text.count(needle)!=1: raise ValueError('post-shader instrumentation hook changed')
        hook.write_text(text.replace(needle,needle+' com.nstut.simplyscreens.testing.visual.VisualClient.afterRender(net.minecraft.client.Minecraft.getInstance());'))

def validate_variant(variant, measurements, directory):
    expected={s['id'] for s in scenarios()}
    if set(measurements)!=expected: raise ValueError('missing shader scenes')
    failures=[]
    for scene in scenarios():
        result=measurements[scene['id']]
        if len(result['frames'])!=sample_count(scene): raise ValueError('incomplete shader samples')
        for index in range(sample_count(scene)):
            frame=json.loads((directory/f'{scene["id"]}-{index:02d}.json').read_text())
            if frame.get('shaderPackActive') is not (variant!='shader-off'):
                raise ValueError('wrong shader state; a disabled/failed shader is not visual evidence')
        if result['status']!='pass' and not scene.get('alpha'): failures.append(scene['id'])
    if variant=='original':
        # A startup/occlusion error cannot count as reproduction: require an actual RGB-chart mismatch.
        if not any(p['median']>8 for s in measurements.values() for f in s['frames'] for p in f.get('patches',[])):
            raise ValueError('INCONCLUSIVE: shader did not reproduce image color distortion')
    elif failures:
        raise ValueError(f'{variant} failed source RGB/occlusion tolerance: {failures}')

def compare_alpha(evidence):
    scene=next(s for s in scenarios() if s.get('alpha'))
    measurements=[]
    for sample in range(sample_count(scene)):
        stem=f'{scene["id"]}-{sample:02d}'
        frame=json.loads((evidence/'fixed'/f'{stem}.json').read_text())
        value=shader_pixels.compare_transparency(evidence/'fixed'/f'{stem}.png',evidence/'original'/f'{stem}.png',frame)
        measurements.append(value)
    if max(measurements)>8: raise ValueError(f'transparent samples changed/double blended: {measurements}')
    return measurements

def compare_bloom(evidence):
    import numpy as np
    from PIL import Image
    scene=next(s for s in scenarios() if s['facing']=='NORTH' and s['distance']==8 and not s['occluded'])
    values=[]
    for sample in range(sample_count(scene)):
        stem=f'{scene["id"]}-{sample:02d}'
        frame=json.loads((evidence/'fixed'/f'{stem}.json').read_text())
        u,v,_,_=shader_pixels.regions(frame)
        # Geometric ring just outside the image; never selected by observed brightness.
        ring=(u>-.08)&(u<1.08)&(v>-.08)&(v<1.08)&((u<-.015)|(u>1.015)|(v<-.015)|(v>1.015))
        if ring.sum()<32: raise ValueError('insufficient glow-ring coverage')
        on=np.asarray(Image.open(evidence/'fixed'/f'{stem}.png').convert('RGB'),dtype=float)
        off=np.asarray(Image.open(evidence/'bloom-off'/f'{stem}.png').convert('RGB'),dtype=float)
        values.append(float(np.mean(on[ring]-off[ring])))
    if min(values)<=0.15: raise ValueError(f'no measurable retained bloom outside image: {values}')
    return values

def run(target):
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip())
    run_id=uuid.uuid4().hex
    evidence=ROOT/'build/shader-evidence'/target/run_id
    stage=ROOT/'build/shader-work'/target/run_id
    evidence.mkdir(parents=True)
    receipt=dict(schema=1,target=target,head=head,dirty=dirty,run_id=run_id,status='running',variants={})
    started=time.monotonic()
    try:
        cache=(ROOT/'build/shader-dependencies').resolve()
        jars,pack=prepare(target,cache)
        receipt['stack']=load_lock()['stacks'][target]
        receipt['shaderpack']=load_lock()['shaderpack']
        standalone=live.packetweave_jar(ROOT,target)
        receipt['source_sha256']=visual.snapshot(ROOT,stage)
        visual.scope_gradle(stage,target)
        instrument(stage)
        hook=stage/('neoforge-26.1.2' if target=='neoforge-26.1.2' else 'common-'+target.split('-',1)[1])/visual.PACKAGE/'client/mixin/GameRendererMixin.java'
        original=hook.read_text()
        for variant in VARIANTS:
            hook.write_text(original.replace('ScreenColorRestore.finishWorld();','ScreenColorRestore.beginFrame();') if variant=='original' else original)
            def configure(client):
                install_pack(client,pack,enabled=variant!='shader-off')
                # Same pack and grading settings. Change only bloom for the glow negative control.
                (client/'shaderpacks'/f'{pack.name}.txt').write_text(
                    'BLOOM_ENABLED='+('-1' if variant=='bloom-off' else '1')+'\nBLOOM_STRENGTH=0.32\n')
            results=visual.run_variant(stage,target,'fixed',evidence/variant,standalone,
                scenario_list=scenarios(),fixture_writer=shader_pixels.write_chart,pixel_measure=shader_pixels.measure,
                runtime_setup=configure,client_args=(f'-PscreensShaderStack={cache}',),validate_outcome=False)
            validate_variant(variant,results,evidence/variant)
            receipt['variants'][variant]='pass'
        receipt['alpha_delta_p95']=compare_alpha(evidence)
        receipt['bloom_ring_delta']=compare_bloom(evidence)
        receipt['status']='pass'
    except BaseException as error:
        receipt['status']='fail';receipt['error']=str(error)
        raise
    finally:
        receipt['seconds']=time.monotonic()-started
        visual.atomic_json(evidence/'result.json',receipt)
    return receipt

def verify_receipts(directory,head):
    receipts=[json.loads(path.read_text()) for path in directory.rglob('result.json')]
    if len(receipts)!=len(TARGETS) or {r.get('target') for r in receipts}!=set(TARGETS):
        raise ValueError('missing or duplicate shader targets')
    lock=load_lock()
    for r in receipts:
        if r.get('status')!='pass' or r.get('dirty') is not False or r.get('head')!=head:
            raise ValueError('failed, dirty, or stale shader evidence')
        if r.get('variants')!={v:'pass' for v in VARIANTS}: raise ValueError('incomplete shader controls')
        if r.get('stack')!=lock['stacks'][r['target']] or r.get('shaderpack')!=lock['shaderpack']:
            raise ValueError('unexpected shader dependencies')
        if len(r.get('alpha_delta_p95',[]))!=4 or max(r['alpha_delta_p95'])>8:
            raise ValueError('missing/failed transparency evidence')
        if len(r.get('bloom_ring_delta',[]))!=4 or min(r['bloom_ring_delta'])<=.15:
            raise ValueError('missing/failed bloom evidence')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target',choices=TARGETS)
    parser.add_argument('--verify-receipts',type=Path)
    parser.add_argument('--head')
    args=parser.parse_args()
    if args.verify_receipts:
        if not args.head: parser.error('--head is required')
        verify_receipts(args.verify_receipts,args.head)
    elif args.target:
        with visual.checkout_lock(ROOT): run(args.target)
    else: parser.error('--target or --verify-receipts required')

if __name__=='__main__': main()
