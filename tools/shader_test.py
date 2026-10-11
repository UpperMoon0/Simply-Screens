#!/usr/bin/env python3
"""Real Iris/Oculus + Complementary source-color regression, with unmodified-world control."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import re
import zipfile
from pathlib import Path
import subprocess
import time
import uuid

import visual_test as visual
import live_join_test as live
from visual_cases import TARGETS, FACES, sample_count
from shader_stack import load_lock, prepare, install_pack, native_mod_ids

ROOT = Path(__file__).resolve().parents[1]
VARIANTS = ('shader-off', 'original', 'fixed', 'original-static', 'fixed-static', 'fixed-repeat', 'bloom-off', 'glow-source-off')
PAIRED_VARIANTS = frozenset(VARIANTS[3:])
PAIR_REFERENCE = 'fixed-static'


# Public options of the pinned pack, not a modification to its source. Keep default
# TAA enabled so the source-color gate still exercises temporal projection jitter.
STATIC_OPTIONS = dict(CLOUD_STYLE_DEFINE='0', CLOUD_SPEED_MULT='0', CLOUD_SHADOWS='false',
                      LIGHTSHAFT_QUALI_DEFINE='0', MOTION_BLUR_EFFECT='-1', WAVING_SPEED='0.00')

def pack_options(variant):
    options=STATIC_OPTIONS | dict(BLOOM_ENABLED='-1' if variant=='bloom-off' else '1', BLOOM_STRENGTH='0.32')
    # Keep the full color/depth matrix on default temporal AA. Only paired glow/alpha
    # controls disable temporal history, whose process-dependent phase is a confound.
    if variant in PAIRED_VARIANTS: options['TAA_DEFINE']='0'
    return options

def verify_pack_options(pack):
    # Fail before launch if an updated pack removes/renames an option; silently ignored
    # properties must not masquerade as a deterministic test environment.
    with zipfile.ZipFile(pack) as archive:
        source=archive.read('shaders/lib/common.glsl').decode()
    for name in pack_options('fixed-static'):
        if not re.search(r'^\s*(?://)?#define\s+'+re.escape(name)+r'\b',source,re.M):
            raise ValueError('unsupported deterministic shader option: '+name)


def scenarios():
    result=[]
    for face in FACES:
        for distance,angle,occluded in ((8,0,False),(32,60,False),(8,0,True),(32,60,True)):
            result.append(dict(id=f'{face}-{distance}-{angle}-{occluded}',facing=face,size=8,
                               distance=distance,angle=angle,occluded=occluded,backdrop=face=='NORTH' and distance==8 and not occluded))
    result.append(dict(id='NORTH-reload',facing='NORTH',size=8,distance=16,angle=0,occluded=False,reload=True))
    result.append(dict(id='NORTH-alpha',facing='NORTH',size=8,distance=16,angle=0,occluded=False,alpha=True,backdrop=True))
    return result

def variant_scenarios(variant):
    if variant not in PAIRED_VARIANTS: return scenarios()
    return [s | dict(settle=True) for s in scenarios() if s['id'] in ('NORTH-8-0-False','NORTH-alpha')]

def instrument(stage):
    visual.instrument(stage)
    # Loader END events are before shader finalization or have ambiguous mixin order.
    # Keep their frame-start hooks; take samples from our post-LevelRenderer hook instead.
    for target in TARGETS:
        for file in (stage/target/'src/main/java').rglob('*.java'):
            text=file.read_text()
            text=text.replace('VisualClient.afterRender(', 'VisualClient.beforeShaderFinalization(')
            file.write_text(text)
        loader = target.split('-', 1)[0]
        if loader == 'fabric':
            setup = stage/target/visual.PACKAGE/'fabric/client/ClientSetup.java'
            lookup = 'net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(id).orElseThrow().getMetadata().getVersion().getFriendlyString()'
        else:
            setup = stage/target/visual.PACKAGE/(f'{loader}/SimplyScreensClient.java' if target == 'neoforge-26.1.2' else f'{loader}/client/ClientSetup.java')
            namespace = 'net.minecraftforge.fml' if loader == 'forge' else 'net.neoforged.fml'
            lookup = namespace+'.ModList.get().getModContainerById(id).orElseThrow().getModInfo().getVersion().toString()'
        visual.replace_once(setup, 'ClientImageManager.initialize();',
            'ClientImageManager.initialize(); com.nstut.simplyscreens.testing.visual.VisualClient.setModLookup(id -> '+lookup+');')
    for module in visual.MODULES:
        base=stage/module/visual.PACKAGE
        server=base/'testing/visual/VisualServer.java'
        command='server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), '
        clock='minecraft:advance_time' if module=='neoforge-26.1.2' else 'doDaylightCycle'
        weather='minecraft:advance_weather' if module=='neoforge-26.1.2' else 'doWeatherCycle'
        visual.replace_once(server, command+'"time set noon");',
            command+f'"gamerule {clock} false");\n            '+
            command+f'"gamerule {weather} false");\n            '+command+'"time set noon");')
        # A bright sky can become darker under a bloom blend, even when the image
        # contributes correctly. A48-block wall covers the entire paired camera frustum,
        # including the16-block alpha view, so sky cannot feed the broad bloom kernel.
        visual.replace_once(server, '            if (request.get("occluded").getAsBoolean()) {',
            '            JsonArray backgrounds = new JsonArray();\n'
            '            if (request.has("backdrop") && request.get("backdrop").getAsBoolean()) {\n'
            '                for (int x = -20; x < 28; x++) for (int y = -20; y < 28; y++) {\n'
            '                    BlockPos pos = anchor.relative(width, x).relative(height, y).relative(facing, -2);\n'
            '                    level.setBlockAndUpdate(pos, Blocks.BLACK_CONCRETE.defaultBlockState());\n'
            '                    placed.add(pos);\n'
            '                    backgrounds.add(JSON.toJsonTree(new int[]{pos.getX(), pos.getY(), pos.getZ()}));\n'
            '                }\n'
            '            }\n'
            '            if (request.get("occluded").getAsBoolean()) {')
        visual.replace_once(server, '            ready.add("occluders", boxes);',
            '            ready.add("occluders", boxes);\n            ready.add("backgrounds", backgrounds);')
        driver=base/'testing/visual/VisualClient.java'
        text=driver.read_text().replace('    public static void afterRender(Minecraft mc) {',
            '    public static void beforeShaderFinalization(Minecraft mc) {}\n\n    public static void afterRender(Minecraft mc) {')
        text=text.replace('    public static void beforeShaderFinalization(Minecraft mc) {}',
            '    private static java.util.function.Function<String, String> modLookup;\n'
            '    public static void setModLookup(java.util.function.Function<String, String> lookup) { modLookup = lookup; }\n'
            '    public static void beforeShaderFinalization(Minecraft mc) {}')
        text=text.replace('        submissions++;',
            '        if (com.nstut.simplyscreens.client.compat.ShaderPackState.isRenderingShadowPass()) return;\n        submissions++;')
        text=text.replace('            frame.addProperty("sample", index);',
            '            frame.addProperty("sample", index);\n            frame.addProperty("shaderPackActive", com.nstut.simplyscreens.client.compat.ShaderPackState.shouldCapture());')
        text=text.replace('            frame.addProperty("sample", index);',
            '            JsonObject loadedMods = new JsonObject();\n'
            '            for (JsonElement id : JsonParser.parseString(Files.readString(DIR.resolve("expected-mods.json"))).getAsJsonArray())\n'
            '                loadedMods.addProperty(id.getAsString(), modLookup.apply(id.getAsString()));\n'
            '            frame.add("loadedShaderMods", loadedMods);\n'
            '            frame.addProperty("sample", index);')
        text=text.replace('        if (anchorUnloadedScenario()) return anchorUnloadSceneSynchronized(mc, image);',
            '        for (JsonElement element : scene.getAsJsonArray("backgrounds")) {\n'
            '            JsonArray cell = element.getAsJsonArray();\n'
            '            BlockPos pos = new BlockPos(cell.get(0).getAsInt(), cell.get(1).getAsInt(), cell.get(2).getAsInt());\n'
            '            if (!mc.level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.BLACK_CONCRETE)) return false;\n'
            '        }\n'
            '        for (JsonElement element : scene.getAsJsonArray("occluders")) {\n'
            '            JsonArray box = element.getAsJsonArray();\n'
            '            BlockPos pos = new BlockPos(box.get(0).getAsInt(), box.get(1).getAsInt(), box.get(2).getAsInt());\n'
            '            if (!mc.level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.RED_CONCRETE)) return false;\n'
            '        }\n'
            '        if (anchorUnloadedScenario()) return anchorUnloadSceneSynchronized(mc, image);')
        text=text.replace('            sampleArmed = false;\n            clearObservedStability();\n            capturing.set(true);',
            '            if (scene.has("settle") && sample == 0 && !ShaderSettling.ready(mc, current)) return;\n'
            '            sampleArmed = false;\n            clearObservedStability();\n            capturing.set(true);')
        text=text.replace('if (++ticks > 1200 && sample < totalSamples)',
            'if (++ticks > (scene.has("settle") ? 4000 : 1200) && sample < totalSamples)')
        driver.write_text(text)
        probe=(stage/'tools/shader/ShaderSettling.java').read_text()
        capture='Screenshot.takeScreenshot(mc.getMainRenderTarget(), ShaderSettling::accept);' if module=='neoforge-26.1.2' else 'accept(Screenshot.takeScreenshot(mc.getMainRenderTarget()));'
        (base/'testing/visual/ShaderSettling.java').write_text(probe.replace('__PROBE__',capture))
        hook=base/'client/mixin/GameRendererMixin.java'
        text=hook.read_text()
        needle='ScreenColorRestore.finishWorld();'
        if text.count(needle)!=1: raise ValueError('post-shader instrumentation hook changed')
        hook.write_text(text.replace(needle,needle+' com.nstut.simplyscreens.testing.visual.VisualClient.afterRender(net.minecraft.client.Minecraft.getInstance());'))

def validate_settling(directory, scene, recompute=False):
    import math
    proof=json.loads((directory/f'{scene}-settling.json').read_text())
    samples=proof.get('samples',[])
    if proof.get('scene')!=scene or proof.get('status')!='pass' or len(samples)!=10:
        raise ValueError('missing completed framebuffer convergence proof')
    if not isinstance(proof.get('probeCount'),int) or proof['probeCount']<10:
        raise ValueError('insufficient convergence probes')
    seconds=proof.get('seconds')
    if not isinstance(seconds,(int,float)) or not math.isfinite(seconds) or not 20<=seconds<=121:
        raise ValueError('invalid convergence duration')
    expected={f'{scene}-settle-{i}.png' for i in range(10)}
    if {p.get('file') for p in samples}!=expected: raise ValueError('missing convergence frame')
    times=[p.get('seconds') for p in samples]
    if any(not isinstance(t,(int,float)) or not math.isfinite(t) or t<0 or t>seconds for t in times):
        raise ValueError('invalid convergence sample time')
    ordered=sorted(times)
    if ordered[-1]-ordered[0]<9 or any(b-a<.9 for a,b in zip(ordered,ordered[1:])):
        raise ValueError('convergence window is too short')
    means=[p.get('means',[]) for p in samples]
    if any(len(m)!=16*12*3 or any(not isinstance(v,(int,float)) or not math.isfinite(v) or not 0<=v<=255 for v in m) for m in means):
        raise ValueError('invalid convergence tile means')
    spread=max(max(channel)-min(channel) for channel in zip(*means))
    if spread>.01 or abs(spread-proof.get('maxTileChannelRange',-1))>1e-9:
        raise ValueError('shader framebuffer is still changing')
    if recompute:
        import numpy as np
        from PIL import Image
        for sample in samples:
            with Image.open(directory/sample['file']) as image:
                rgb=np.asarray(image.convert('RGB'),dtype=float)
            if rgb.shape!=(720,960,3): raise ValueError('wrong convergence image dimensions')
            measured=rgb.reshape(12,60,16,60,3).mean(axis=(1,3)).reshape(-1)
            if not np.allclose(measured,sample['means'],rtol=0,atol=1e-9):
                raise ValueError('convergence metadata does not match framebuffer')
    return proof

def validate_variant(variant, measurements, directory):
    expected={s['id'] for s in variant_scenarios(variant)}
    if set(measurements)!=expected: raise ValueError('missing shader scenes')
    failures=[]
    expected_mods=set(json.loads((directory/'expected-mods.json').read_text()))
    for scene in variant_scenarios(variant):
        if variant in PAIRED_VARIANTS: validate_settling(directory,scene['id'])
        result=measurements[scene['id']]
        if len(result['frames'])!=sample_count(scene): raise ValueError('incomplete shader samples')
        for index in range(sample_count(scene)):
            frame=json.loads((directory/f'{scene["id"]}-{index:02d}.json').read_text())
            if set(frame.get('loadedShaderMods',{}))!=expected_mods or not all(frame['loadedShaderMods'].values()):
                raise ValueError('missing actually loaded renderer/Extra/shader mods')
            if frame.get('shaderPackActive') is not (variant!='shader-off'):
                raise ValueError('wrong shader state; a disabled/failed shader is not visual evidence')
        if result['status']!='pass' and not scene.get('alpha'): failures.append(scene['id'])
    if variant.startswith('original'):
        # A startup/occlusion error cannot count as reproduction: require an actual RGB-chart mismatch.
        if not reproduces_distortion(measurements):
            raise ValueError('INCONCLUSIVE: shader did not reproduce image color distortion')
    elif failures:
        raise ValueError(f'{variant} failed source RGB/occlusion tolerance: {failures}')

def recognizable_chart(frame):
    # A missing/black world draw is not a color-distortion reproduction. Require
    # spatial gray contrast and the three known primary-color channel orderings.
    patches={p['index']:p for p in frame.get('patches',[]) if 'observed' in p}
    if not all(i in patches for i in (0,3,4,5,6)): return False
    if sum(patches[3]['observed'])-sum(patches[0]['observed'])<3*16: return False
    for index,channel in ((4,0),(5,1),(6,2)):
        rgb=patches[index]['observed']
        if rgb[channel]-max(rgb[c] for c in range(3) if c!=channel)<8: return False
    return True

def reproduces_distortion(measurements):
    # Far vanilla fog and occluders cannot prove shader damage. Every captured
    # near chart used as reproduction must still contain the recognizable image.
    near={scene['id'] for scene in scenarios() if scene['distance']<=16
          and not scene.get('alpha') and not scene['occluded']}
    frames=[f for name,s in measurements.items() if name in near for f in s['frames']]
    return bool(frames) and all(recognizable_chart(f) for f in frames) and any(
        p['median']>8 for f in frames for p in f.get('patches',[]))

def compare_alpha(evidence):
    import shader_pixels
    scene=next(s for s in scenarios() if s.get('alpha'))
    measurements=[]
    for sample in range(sample_count(scene)):
        stem=f'{scene["id"]}-{sample:02d}'
        frame=json.loads((evidence/PAIR_REFERENCE/f'{stem}.json').read_text())
        value=shader_pixels.compare_transparency(evidence/PAIR_REFERENCE/f'{stem}.png',evidence/'original-static'/f'{stem}.png',frame)
        measurements.append(value)
    if max(measurements)>8: raise ValueError(f'transparent samples changed/double blended: {measurements}')
    return measurements

def ring_delta(evidence, control, sample):
    import shader_pixels
    import numpy as np
    from PIL import Image
    scene=next(s for s in scenarios() if s['facing']=='NORTH' and s['distance']==8 and not s['occluded'])
    stem=f'{scene["id"]}-{sample:02d}'
    frame=json.loads((evidence/PAIR_REFERENCE/f'{stem}.json').read_text())
    u,v,_,_=shader_pixels.regions(frame)
    # Geometric ring just outside the image; never selected by observed brightness.
    ring=(u>-.08)&(u<1.08)&(v>-.08)&(v<1.08)&((u<-.015)|(u>1.015)|(v<-.015)|(v>1.015))
    if ring.sum()<32: raise ValueError('insufficient glow-ring coverage')
    on=np.asarray(Image.open(evidence/PAIR_REFERENCE/f'{stem}.png').convert('RGB'),dtype=float)
    off=np.asarray(Image.open(evidence/control/f'{stem}.png').convert('RGB'),dtype=float)
    delta=on[ring]-off[ring]
    return float(np.mean(delta)),float(np.mean(np.abs(delta)))

def validate_stability(values):
    for key,limit in (('ring_signed',.05),('ring_absolute',1.),('alpha_p95',1.)):
        samples=values.get(key,[])
        if len(samples)!=4 or any(not isinstance(x,(int,float)) or not __import__('math').isfinite(x) or abs(x)>limit for x in samples):
            raise ValueError('unstable paired shader background: '+key+' '+repr(samples))

def compare_stability(evidence):
    import shader_pixels
    scene=next(s for s in scenarios() if s.get('alpha'))
    values=dict(ring_signed=[],ring_absolute=[],alpha_p95=[])
    for sample in range(4):
        signed,absolute=ring_delta(evidence,'fixed-repeat',sample)
        values['ring_signed'].append(signed);values['ring_absolute'].append(absolute)
        stem=f'{scene["id"]}-{sample:02d}'
        frame=json.loads((evidence/PAIR_REFERENCE/f'{stem}.json').read_text())
        values['alpha_p95'].append(shader_pixels.compare_transparency(
            evidence/PAIR_REFERENCE/f'{stem}.png',evidence/'fixed-repeat'/f'{stem}.png',frame))
    # An identical second process must have less than one third of the required glow
    # signal. This rejects cloud/time drift rather than crediting it as image bloom.
    validate_stability(values)
    return values

def compare_bloom(evidence, control='bloom-off'):
    values=[ring_delta(evidence,control,sample)[0] for sample in range(4)]
    if min(values)<=0.15: raise ValueError(f'no measurable retained bloom outside image: {values}')
    return values

def suppress_world_image(source, module):
    """Negative control removes only the world's image contribution, never the late replay."""
    if module == 'common-1.20.1':
        needle = '        buildTexturedQuad(vertexBuffer, pose, packedOverlay, tile);'
        if source.count(needle) != 1: raise ValueError('world-image control anchor changed')
        return source.replace(needle, '')
    prefix = ('        addVertex(consumer, pose, packedOverlay,' if module == 'common-1.21.1'
              else '        vertex(consumer, pose,')
    lines = source.splitlines()
    if sum(line.startswith(prefix) for line in lines) != 4: raise ValueError('world-image quad control changed')
    return '\n'.join(line for line in lines if not line.startswith(prefix))+'\n'

def run(target):
    import shader_pixels
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
        verify_pack_options(pack)
        receipt['pack_options']={v:pack_options(v) for v in VARIANTS}
        standalone=live.packetweave_jar(ROOT,target)
        receipt['source_sha256']=visual.snapshot(ROOT,stage)
        visual.scope_gradle(stage,target)
        instrument(stage)
        module='neoforge-26.1.2' if target=='neoforge-26.1.2' else 'common-'+target.split('-',1)[1]
        hook=stage/module/visual.PACKAGE/'client/mixin/GameRendererMixin.java'
        renderer=stage/module/visual.PACKAGE/'client/renderers/ScreenBlockEntityRenderer.java'
        renderer_original=renderer.read_text()
        original=hook.read_text()
        ids=sorted({id for jar in jars for id in native_mod_ids(jar,target.split('-',1)[0])})
        for variant in VARIANTS:
            print(f"=== {target}: shader variant {variant} ===", flush=True)
            renderer.write_text(suppress_world_image(renderer_original,module) if variant=='glow-source-off' else renderer_original)
            hook.write_text(original.replace('ScreenColorRestore.finishWorld();','ScreenColorRestore.beginFrame();') if variant.startswith('original') else original)
            def configure(client):
                install_pack(client,pack,enabled=variant!='shader-off',loader=target.split('-',1)[0])
                # Same pack and grading settings. Change only bloom for the glow negative control.
                (client/'shaderpacks'/f'{pack.name}.txt').write_text(
                    ''.join(f'{key}={value}\n' for key,value in pack_options(variant).items()))
            def fixtures(directory):
                shader_pixels.write_chart(directory)
                visual.atomic_json(directory/'expected-mods.json',ids)
            results=visual.run_variant(stage,target,'fixed',evidence/variant,standalone,
                scenario_list=variant_scenarios(variant),fixture_writer=fixtures,pixel_measure=lambda path, frame: shader_pixels.measure(path,frame,
                    tolerance=12 if variant=='shader-off' and frame['distance']>=32 else shader_pixels.TOLERANCE),
                runtime_setup=configure,client_args=(f'-PscreensShaderStack={cache}',),validate_outcome=False)
            if variant in PAIRED_VARIANTS:
                for scene in variant_scenarios(variant): validate_settling(evidence/variant,scene['id'],recompute=True)
            validate_variant(variant,results,evidence/variant)
            graphics=json.loads((evidence/variant/'graphics.json').read_text())
            if 'graphics' in receipt and receipt['graphics']!=graphics: raise ValueError('graphics backend changed between shader controls')
            receipt['graphics']=graphics
            receipt['variants'][variant]='pass'
        receipt['repeat_stability']=compare_stability(evidence)
        receipt['alpha_delta_p95']=compare_alpha(evidence)
        receipt['bloom_ring_delta']=compare_bloom(evidence)
        receipt['screen_glow_ring_delta']=compare_bloom(evidence,'glow-source-off')
        receipt['evidence_sha256']={str(p.relative_to(evidence)):hashlib.sha256(p.read_bytes()).hexdigest()
                                    for p in evidence_files(evidence)}
        receipt['status']='pass'
    except BaseException as error:
        receipt['status']='fail';receipt['error']=str(error)
        for log in sorted(evidence.rglob('*-process.log')):
            print(f'\n--- {log.relative_to(evidence)} (last 120 lines) ---', flush=True)
            print('\n'.join(log.read_text(errors="replace").splitlines()[-120:]), flush=True)
        raise
    finally:
        receipt['seconds']=time.monotonic()-started
        visual.atomic_json(evidence/'result.json',receipt)
    return receipt

def evidence_files(directory):
    paths=[]
    for variant in VARIANTS:
        base=directory/variant
        paths += [base/name for name in ('expected-mods.json','graphics.json','measurements.json')]
        for scene in variant_scenarios(variant):
            if variant in PAIRED_VARIANTS:
                paths += [base/f'{scene["id"]}-settling.json']
                paths += [base/f'{scene["id"]}-settle-{i}.png' for i in range(10)]
            for sample in range(sample_count(scene)):
                paths += [base/f'{scene["id"]}-{sample:02d}.{extension}' for extension in ('png','json')]
    return paths

def verify_receipts(directory,head):
    paths=list(directory.rglob('result.json'))
    receipts=[json.loads(path.read_text()) for path in paths]
    if len(receipts)!=len(TARGETS) or {r.get('target') for r in receipts}!=set(TARGETS):
        raise ValueError('missing or duplicate shader targets')
    lock=load_lock()
    for path,r in zip(paths,receipts):
        if r.get('status')!='pass' or r.get('dirty') is not False or r.get('head')!=head:
            raise ValueError('failed, dirty, or stale shader evidence')
        if r.get('variants')!={v:'pass' for v in VARIANTS}: raise ValueError('incomplete shader controls')
        if r.get('pack_options')!={v:pack_options(v) for v in VARIANTS}: raise ValueError('wrong deterministic shader profile')
        validate_stability(r.get('repeat_stability',{}))
        if r.get('stack')!=lock['stacks'][r['target']] or r.get('shaderpack')!=lock['shaderpack']:
            raise ValueError('unexpected shader dependencies')
        hashes=r.get('evidence_sha256',{})
        expected_files=evidence_files(path.parent)
        if set(hashes)!={str(p.relative_to(path.parent)) for p in expected_files}:
            raise ValueError('missing shader framebuffer evidence')
        for file in expected_files:
            if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest()!=hashes[str(file.relative_to(path.parent))]:
                raise ValueError('missing or changed shader framebuffer evidence')
        for variant in VARIANTS:
            base=path.parent/variant
            graphics=json.loads((base/'graphics.json').read_text())
            if graphics!=r.get('graphics') or not all(graphics.get(k) for k in ('graphicsVendor','graphicsRenderer','graphicsVersion')):
                raise ValueError('missing or inconsistent graphics evidence')
            validate_variant(variant,json.loads((base/'measurements.json').read_text()),base)
        if len(r.get('alpha_delta_p95',[]))!=4 or max(r['alpha_delta_p95'])>8:
            raise ValueError('missing/failed transparency evidence')
        for key in ('bloom_ring_delta','screen_glow_ring_delta'):
            if len(r.get(key,[]))!=4 or min(r[key])<=.15:
                raise ValueError('missing/failed screen-specific bloom evidence')

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
