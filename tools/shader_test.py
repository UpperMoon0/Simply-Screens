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
VARIANTS = ('shader-off', 'original', 'fixed', 'original-static', 'fixed-static', 'fixed-repeat', 'fixed-repeat-2', 'bloom-off', 'glow-source-off', 'source-and-bloom-off')
PAIRED_VARIANTS = frozenset(VARIANTS[3:])
PAIR_REFERENCE = 'fixed-static'
ON_REPEATS = ('fixed-static','fixed-repeat','fixed-repeat-2')
# Keep all comparison/repeat controls on one runner; only independent suites split.
SHARDS = {'temporal': VARIANTS[:3], 'paired': VARIANTS[3:]}

def selected_variants(shard=None):
    return VARIANTS if shard is None else SHARDS[shard]

def coverage_manifest():
    return {v: {'options': pack_options(v),
                'scenarios': [dict(s, samples=sample_count(s)) for s in variant_scenarios(v)]}
            for v in VARIANTS}

MIN_GLOW = .15
EFFECT_NOISE_MULTIPLIER = 3.
INTERACTION_NOISE_MULTIPLIER = 6.


# Public options of the pinned pack, not a modification to its source. Keep default
# TAA enabled so the source-color gate still exercises temporal projection jitter.
STATIC_OPTIONS = dict(CLOUD_STYLE_DEFINE='0', CLOUD_SPEED_MULT='0', CLOUD_SHADOWS='false',
                      LIGHTSHAFT_QUALI_DEFINE='0', MOTION_BLUR_EFFECT='-1', WAVING_SPEED='0.00')

def pack_options(variant):
    options=STATIC_OPTIONS | dict(BLOOM_ENABLED='-1' if variant in ('bloom-off','source-and-bloom-off') else '1', BLOOM_STRENGTH='0.32')
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
        text=text.replace('            frame.addProperty("sample", index);',
            '            frame.addProperty("sample", index);\n'
            '            frame.addProperty("runtimeJavaVersion", System.getProperty("java.runtime.version"));\n'
            '            frame.addProperty("runtimeJavaVendor", System.getProperty("java.vendor"));\n'
            '            frame.addProperty("runtimeArchitecture", System.getProperty("os.arch"));')
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
        text=text.replace('            frame.addProperty("fov", 70);',
            '            frame.addProperty("fov", 70);\n            frame.add("shaderDiagnostics", ShaderDiagnostics.capture(mc));')
        driver.write_text(text)
        probe=(stage/'tools/shader/ShaderSettling.java').read_text()
        capture='Screenshot.takeScreenshot(mc.getMainRenderTarget(), ShaderSettling::accept);' if module=='neoforge-26.1.2' else 'accept(Screenshot.takeScreenshot(mc.getMainRenderTarget()));'
        (base/'testing/visual/ShaderSettling.java').write_text(probe.replace('__PROBE__',capture))
        (base/'testing/visual/ShaderDiagnostics.java').write_text((stage/'tools/shader/ShaderDiagnostics.java').read_text())
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
    reported=proof.get('maxTileChannelRange')
    if not isinstance(reported,(int,float)) or not math.isfinite(reported) or spread>.01 or abs(spread-reported)>1e-9:
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
    runtime=json.loads((directory/'graphics.json').read_text())
    for scene in variant_scenarios(variant):
        if variant in PAIRED_VARIANTS: validate_settling(directory,scene['id'])
        result=measurements[scene['id']]
        if len(result['frames'])!=sample_count(scene): raise ValueError('incomplete shader samples')
        for index in range(sample_count(scene)):
            frame=json.loads((directory/f'{scene["id"]}-{index:02d}.json').read_text())
            if not isinstance(frame.get('shaderDiagnostics'),dict) or len(frame['shaderDiagnostics'].get('renderModelView',[]))!=16:
                raise ValueError('missing shader setup diagnostics')
            if set(frame.get('loadedShaderMods',{}))!=expected_mods or not all(frame['loadedShaderMods'].values()):
                raise ValueError('missing actually loaded renderer/Extra/shader mods')
            for key in ('runtimeJavaVersion','runtimeJavaVendor','runtimeArchitecture'):
                if not frame.get(key) or frame[key]!=runtime.get(key):
                    raise ValueError('missing or inconsistent actual client runtime identity')
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

def finite_samples(samples, limit=None):
    import math
    return (isinstance(samples,list) and len(samples)==4 and all(
        isinstance(x,(int,float)) and math.isfinite(x) and (limit is None or abs(x)<=limit) for x in samples))

def compare_alpha(evidence):
    import shader_pixels
    scene=next(s for s in scenarios() if s.get('alpha'))
    measurements={}
    for reference in ON_REPEATS:
        values=[]
        for sample in range(4):
            stem=f'{scene["id"]}-{sample:02d}'
            frame=json.loads((evidence/reference/f'{stem}.json').read_text())
            values.append(shader_pixels.compare_transparency(evidence/reference/f'{stem}.png',evidence/'original-static'/f'{stem}.png',frame))
        if max(values)>8: raise ValueError(f'transparent samples changed/double blended: {reference} {values}')
        measurements[reference]=values
    return measurements

def ring_delta(evidence, control, sample, reference=PAIR_REFERENCE):
    import shader_pixels
    import numpy as np
    from PIL import Image
    scene=next(s for s in scenarios() if s['facing']=='NORTH' and s['distance']==8 and not s['occluded'])
    stem=f'{scene["id"]}-{sample:02d}'
    frame=json.loads((evidence/reference/f'{stem}.json').read_text())
    other=json.loads((evidence/control/f'{stem}.json').read_text())
    for key in ('camera','cameraYaw','cameraPitch','center','normal','right','up','size','fov','occluders','backgrounds'):
        if frame.get(key)!=other.get(key): raise ValueError('paired shader geometry changed: '+key)
    u,v,_,_=shader_pixels.regions(frame)
    # Predeclared geometric ring, never selected by observed brightness.
    ring=(u>-.08)&(u<1.08)&(v>-.08)&(v<1.08)&((u<-.015)|(u>1.015)|(v<-.015)|(v>1.015))
    if ring.sum()<32: raise ValueError('insufficient glow-ring coverage')
    on=np.asarray(Image.open(evidence/reference/f'{stem}.png').convert('RGB'),dtype=float)
    off=np.asarray(Image.open(evidence/control/f'{stem}.png').convert('RGB'),dtype=float)
    delta=on[ring]-off[ring]
    return float(np.mean(delta)),float(np.mean(np.abs(delta)))

def repeat_pairs():
    from itertools import combinations
    return list(combinations(ON_REPEATS,2))

def validate_stability(values):
    pairs=values.get('pairs',{})
    if set(pairs)!={a+'/'+b for a,b in repeat_pairs()}: raise ValueError('missing independent shader repeats')
    uncertainty=0.
    for pair,measurements in pairs.items():
        for key,limit in (('ring_signed',1.),('ring_absolute',1.),('alpha_p95',1.)):
            if not finite_samples(measurements.get(key),limit):
                raise ValueError('unstable paired shader background: '+pair+' '+key)
        for signed,absolute in zip(measurements['ring_signed'],measurements['ring_absolute']):
            if absolute<0 or abs(signed)>absolute+1e-9: raise ValueError('invalid repeat noise measurement')
            uncertainty=max(uncertainty,absolute)
    reported=values.get('uncertainty')
    if not finite_samples([reported]*4,1) or reported<0 or abs(reported-uncertainty)>1e-9: raise ValueError('wrong empirical repeat uncertainty')
    return uncertainty

def compare_stability(evidence):
    import shader_pixels
    scene=next(s for s in scenarios() if s.get('alpha'))
    values=dict(pairs={})
    for reference,control in repeat_pairs():
        pair=dict(ring_signed=[],ring_absolute=[],alpha_p95=[])
        for sample in range(4):
            signed,absolute=ring_delta(evidence,control,sample,reference)
            pair['ring_signed'].append(signed);pair['ring_absolute'].append(absolute)
            stem=f'{scene["id"]}-{sample:02d}'
            frame=json.loads((evidence/reference/f'{stem}.json').read_text())
            pair['alpha_p95'].append(shader_pixels.compare_transparency(
                evidence/reference/f'{stem}.png',evidence/control/f'{stem}.png',frame))
        values['pairs'][reference+'/'+control]=pair
    # Engineering nuisance envelope, not a statistical confidence interval. Raster
    # phases are correlated; never treat four frames as independent processes.
    values['uncertainty']=max(x for p in values['pairs'].values() for x in p['ring_absolute'])
    validate_stability(values)
    return values

def validate_glow(values, uncertainty):
    if not finite_samples([uncertainty]*4,1) or uncertainty<0: raise ValueError('invalid nuisance bound')
    background=values.get('background_bloom')
    if not finite_samples(background): raise ValueError('missing background-only bloom contrast')
    for key,multiplier in (('bloom',EFFECT_NOISE_MULTIPLIER),('source',EFFECT_NOISE_MULTIPLIER),
                           ('interaction',INTERACTION_NOISE_MULTIPLIER)):
        group=values.get(key,{})
        if set(group)!=set(ON_REPEATS): raise ValueError('missing factorial glow references')
        for reference,samples in group.items():
            if not finite_samples(samples) or min(samples)<=MIN_GLOW+multiplier*uncertainty:
                raise ValueError(f'no glow separated from measured nuisance: {key} {reference} {samples}; U={uncertainty}')
    for reference in ON_REPEATS:
        if any(abs(i-(b-a))>1e-9 for i,b,a in zip(values['interaction'][reference],values['bloom'][reference],background)):
            raise ValueError('invalid factorial interaction')

def compare_glow(evidence, uncertainty):
    background=[ring_delta(evidence,'source-and-bloom-off',sample,'glow-source-off')[0] for sample in range(4)]
    values=dict(bloom={},source={},interaction={},background_bloom=background)
    for reference in ON_REPEATS:
        bloom=[ring_delta(evidence,'bloom-off',sample,reference)[0] for sample in range(4)]
        values['bloom'][reference]=bloom
        values['source'][reference]=[ring_delta(evidence,'glow-source-off',sample,reference)[0] for sample in range(4)]
        values['interaction'][reference]=[a-b for a,b in zip(bloom,background)]
    validate_glow(values,uncertainty)
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

def run(target, shard=None):
    import shader_pixels
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip())
    run_id=uuid.uuid4().hex
    evidence=ROOT/'build/shader-evidence'/target/run_id
    stage=ROOT/'build/shader-work'/target/run_id
    evidence.mkdir(parents=True)
    variants=selected_variants(shard)
    receipt=dict(schema=2,target=target,shard=shard,head=head,dirty=dirty,run_id=run_id,status='running',variants={},
                 coverage={v:coverage_manifest()[v] for v in variants}, variant_seconds={},
                 cache={'shader_downloads':os.environ.get('SHADER_DOWNLOAD_CACHE_HIT','unknown')},
                 resources={'logical_cpus':os.cpu_count(), 'runner_image':os.environ.get('ImageVersion','unknown')})
    started=time.monotonic()
    try:
        cache=(ROOT/'build/shader-dependencies').resolve()
        jars,pack=prepare(target,cache)
        receipt['stack']=load_lock()['stacks'][target]
        receipt['shaderpack']=load_lock()['shaderpack']
        verify_pack_options(pack)
        receipt['pack_options']={v:pack_options(v) for v in variants}
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
        for variant in variants:
            variant_started=time.monotonic()
            print(f"=== {target}: shader variant {variant} ===", flush=True)
            renderer.write_text(suppress_world_image(renderer_original,module) if variant in ('glow-source-off','source-and-bloom-off') else renderer_original)
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
            graphics=json.loads((evidence/variant/'graphics.json').read_text())
            first=variant_scenarios(variant)[0]['id']
            frame=json.loads((evidence/variant/f'{first}-00.json').read_text())
            graphics.update({k:frame.get(k) for k in ('runtimeJavaVersion','runtimeJavaVendor','runtimeArchitecture')})
            visual.atomic_json(evidence/variant/'graphics.json',graphics)
            validate_variant(variant,results,evidence/variant)
            if 'graphics' in receipt and receipt['graphics']!=graphics: raise ValueError('graphics backend changed between shader controls')
            receipt['graphics']=graphics
            receipt['variants'][variant]='pass'
            receipt['variant_seconds'][variant]=time.monotonic()-variant_started
        if shard in (None, 'paired'):
            receipt['repeat_stability']=compare_stability(evidence)
            receipt['alpha_delta_p95']=compare_alpha(evidence)
            receipt['glow_evidence']=compare_glow(evidence,receipt['repeat_stability']['uncertainty'])
        receipt['evidence_sha256']={str(p.relative_to(evidence)):hashlib.sha256(p.read_bytes()).hexdigest()
                                    for p in evidence_files(evidence, variants)}
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

def evidence_files(directory, variants=VARIANTS):
    paths=[]
    for variant in variants:
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
    sharded=any(r.get('shard') is not None for r in receipts)
    expected={(target,shard) for target in TARGETS for shard in (SHARDS if sharded else (None,))}
    identities=[(r.get('target'),r.get('shard')) for r in receipts]
    if len(identities)!=len(expected) or set(identities)!=expected:
        raise ValueError('missing or duplicate shader targets/shards')
    lock=load_lock()
    source_hashes=set()
    for path,r in zip(paths,receipts):
        if r.get('schema')!=2 or r.get('status')!='pass' or r.get('dirty') is not False or r.get('head')!=head:
            raise ValueError('failed, dirty, stale, or unsupported shader evidence')
        source_hash=r.get('source_sha256','')
        if not re.fullmatch('[0-9a-f]{64}',source_hash): raise ValueError('missing source identity')
        source_hashes.add(source_hash)
        variants=selected_variants(r.get('shard'))
        if r.get('variants')!={v:'pass' for v in variants}: raise ValueError('incomplete shader controls')
        if r.get('coverage')!={v:coverage_manifest()[v] for v in variants}: raise ValueError('changed scenario manifest')
        if r.get('pack_options')!={v:pack_options(v) for v in variants}: raise ValueError('wrong deterministic shader profile')
        if r.get('stack')!=lock['stacks'][r['target']] or r.get('shaderpack')!=lock['shaderpack']:
            raise ValueError('unexpected shader dependencies')
        hashes=r.get('evidence_sha256',{})
        expected_files=evidence_files(path.parent,variants)
        if set(hashes)!={str(p.relative_to(path.parent)) for p in expected_files}:
            raise ValueError('missing shader framebuffer evidence')
        for file in expected_files:
            if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest()!=hashes[str(file.relative_to(path.parent))]:
                raise ValueError('missing or changed shader framebuffer evidence')
        for variant in variants:
            base=path.parent/variant
            graphics=json.loads((base/'graphics.json').read_text())
            if graphics!=r.get('graphics') or not all(graphics.get(k) for k in ('graphicsVendor','graphicsRenderer','graphicsVersion')):
                raise ValueError('missing or inconsistent graphics evidence')
            validate_variant(variant,json.loads((base/'measurements.json').read_text()),base)
        if r.get('shard') in (None,'paired'):
            validate_stability(r.get('repeat_stability',{}))
            alpha=r.get('alpha_delta_p95',{})
            if set(alpha)!=set(ON_REPEATS) or any(not finite_samples(v,8) for v in alpha.values()):
                raise ValueError('missing/failed transparency evidence')
            validate_glow(r.get('glow_evidence',{}),validate_stability(r['repeat_stability']))
    if len(source_hashes)!=1: raise ValueError('shader shards used different source snapshots')
    # Hardware variation must never silently change the original backend contract.
    for target in TARGETS:
        graphics=[r['graphics'] for r in receipts if r['target']==target]
        if any(g!=graphics[0] for g in graphics): raise ValueError('graphics backend changed between shader shards')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target',choices=TARGETS)
    parser.add_argument('--verify-receipts',type=Path)
    parser.add_argument('--head')
    parser.add_argument('--shard', choices=SHARDS)
    parser.add_argument('--matrix', action='store_true')
    parser.add_argument('--manifest', action='store_true')
    args=parser.parse_args()
    if args.matrix:
        print(json.dumps({'include':[dict(target=t,shard=s) for t in TARGETS for s in SHARDS]}))
    elif args.manifest:
        print(json.dumps(coverage_manifest(),sort_keys=True))
    elif args.verify_receipts:
        if not args.head: parser.error('--head is required')
        verify_receipts(args.verify_receipts,args.head)
    elif args.target:
        with visual.checkout_lock(ROOT): run(args.target,args.shard)
    else: parser.error('--target or --verify-receipts required')

if __name__=='__main__': main()
