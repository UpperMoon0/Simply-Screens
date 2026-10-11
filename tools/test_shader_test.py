import copy
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
from unittest.mock import patch
import numpy as np
from PIL import Image
import shader_pixels as pixels
import shader_stack
import shader_test

class ShaderOracleTests(unittest.TestCase):
    def setUp(self):
        self.frame=dict(camera=[0,0,-8],center=[0,0,0],normal=[0,0,-1],right=[-1,0,0],up=[0,1,0],
                        cameraYaw=0,cameraPitch=0,fov=70,size=8,occluders=[],occluded=False)
    def chart(self, frame, grade=False, leak=False):
        u,v,visible,hidden=pixels.regions(frame)
        rgb=np.zeros((720,960,3),dtype=np.uint8)+30
        valid=visible|hidden
        iy=np.clip((v*4).astype(int),0,3);ix=np.clip((u*4).astype(int),0,3)
        rgb[valid]=pixels.PALETTE[iy[valid],ix[valid]].astype(np.uint8)
        if grade: rgb[valid]=(rgb[valid].astype(float)*.6+20).astype(np.uint8)
        if not leak: rgb[hidden]=[70,3,3]
        return rgb
    def measure(self, array, frame):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'frame.png';Image.fromarray(array).save(p)
            return pixels.measure(p,frame)
    def test_exact_chart_passes(self):
        result=self.measure(self.chart(self.frame),self.frame)
        self.assertEqual('pass',result['status']);self.assertEqual(16,len(result['patches']))
    def test_shader_grade_is_rejected(self):
        result=self.measure(self.chart(self.frame,grade=True),self.frame)
        self.assertEqual('pixel-failure',result['status'])
        self.assertGreater(max(p['median'] for p in result['patches']),8)
    def test_blank_or_missing_image_is_rejected(self):
        self.assertEqual('pixel-failure',self.measure(np.zeros((720,960,3),dtype=np.uint8),self.frame)['status'])
    def test_depth_leak_is_rejected(self):
        frame=self.frame|dict(occluded=True,occluders=[[-5,-5,-2,0,5,-1]])
        self.assertEqual('pass',self.measure(self.chart(frame),frame)['status'])
        self.assertEqual('pixel-failure',self.measure(self.chart(frame,leak=True),frame)['status'])
    def test_error_budget_is_not_screenshot_equality(self):
        rgb=self.chart(self.frame).astype(np.int16)
        u,v,visible,_=pixels.regions(self.frame)
        rgb[visible]=np.minimum(rgb[visible]+3,255)
        self.assertEqual('pass',self.measure(rgb.astype(np.uint8),self.frame)['status'])
    def test_vanilla_fog_budget_does_not_relax_shader_budget(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'frame.png'
            Image.fromarray(np.minimum(self.chart(self.frame).astype(int)+9,255).astype(np.uint8)).save(p)
            self.assertEqual('pixel-failure',pixels.measure(p,self.frame)['status'])
            self.assertEqual('pass',pixels.measure(p,self.frame,tolerance=12)['status'])

    def test_alpha_comparison_rejects_double_blending(self):
        with tempfile.TemporaryDirectory() as d:
            p,q=Path(d)/'a.png',Path(d)/'b.png'
            a=np.full((720,960,3),80,dtype=np.uint8);Image.fromarray(a).save(p);Image.fromarray(a+30).save(q)
            self.assertEqual(30,pixels.compare_transparency(p,q,self.frame))

class ShaderStackTests(unittest.TestCase):
    def test_locked_targets_are_loader_and_game_compatible(self):
        lock=shader_stack.load_lock()
        self.assertEqual(set(shader_test.TARGETS),set(lock['stacks']))
        self.assertTrue(all(len(a['sha512'])==128 for a in lock['stacks']['fabric-1.21.1']))
    def test_checksum_mismatch_is_never_installed(self):
        artifact=dict(filename='bad.jar',url='https://cdn.modrinth.com/nope',sha512='0'*128)
        import io
        with tempfile.TemporaryDirectory() as d,patch('shader_stack.urllib.request.urlopen',return_value=io.BytesIO(b'wrong')):
            with self.assertRaisesRegex(ValueError,'checksum mismatch'): shader_stack.download(artifact,Path(d))
            self.assertFalse((Path(d)/'bad.jar').exists())
    def test_disabled_shader_config_is_explicit(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);pack=root/'test.zip';pack.write_bytes(b'test')
            shader_stack.install_pack(root/'client',pack,False)
            self.assertIn('enableShaders=false',(root/'client/config/iris.properties').read_text())
    def test_oculus_uses_its_native_config_filename_for_on_and_off(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);pack=root/'test.zip';pack.write_bytes(b'test')
            for loader in ('fabric','forge','neoforge'):
                for enabled in (False,True):
                    client=root/(loader+str(enabled))
                    shader_stack.install_pack(client,pack,enabled,loader=loader)
                    filename='oculus.properties' if loader=='forge' else 'iris.properties'
                    self.assertEqual(f'enableShaders={str(enabled).lower()}\nshaderPack=test.zip\n',
                                     (client/'config'/filename).read_text())
                    self.assertEqual([filename],[p.name for p in (client/'config').iterdir()])

    def test_glow_control_removes_world_vertices_but_keeps_replay(self):
        for module in shader_test.visual.MODULES:
            path=shader_test.ROOT/module/shader_test.visual.PACKAGE/'client/renderers/ScreenBlockEntityRenderer.java'
            altered=shader_test.suppress_world_image(path.read_text(),module)
            self.assertIn('ScreenColorRestore.capture',altered)
            self.assertIn('textPolygonOffset',altered)
            self.assertNotEqual(path.read_text(),altered)

    def test_receipt_cli_does_not_require_pixel_dependencies(self):
        with tempfile.TemporaryDirectory() as d:
            result=subprocess.run([sys.executable,'-S',str(shader_test.ROOT/'tools/shader_test.py'),
                                   '--verify-receipts',d,'--head','abc'],capture_output=True,text=True)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('missing or duplicate shader targets',result.stderr)
        self.assertNotIn('ModuleNotFoundError',result.stderr)

    def test_real_checkout_instrumentation_has_post_shader_capture_and_mod_proof(self):
        with tempfile.TemporaryDirectory() as d:
            stage=Path(d)
            shader_test.visual.snapshot(shader_test.ROOT,stage)
            shader_test.instrument(stage)
            for module in shader_test.visual.MODULES:
                base=stage/module/shader_test.visual.PACKAGE
                self.assertIn('loadedShaderMods',(base/'testing/visual/VisualClient.java').read_text())
                self.assertIn('Blocks.RED_CONCRETE',(base/'testing/visual/VisualClient.java').read_text())
                self.assertIn('Blocks.BLACK_CONCRETE',(base/'testing/visual/VisualClient.java').read_text())
                server=(base/'testing/visual/VisualServer.java').read_text()
                self.assertIn('Blocks.BLACK_CONCRETE',server)
                self.assertIn('gamerule '+('minecraft:advance_time' if module=='neoforge-26.1.2' else 'doDaylightCycle')+' false',server)
                self.assertIn('gamerule '+('minecraft:advance_weather' if module=='neoforge-26.1.2' else 'doWeatherCycle')+' false',server)
                hook=(base/'client/mixin/GameRendererMixin.java').read_text()
                self.assertIn('ScreenColorRestore.finishWorld(); com.nstut.simplyscreens.testing.visual.VisualClient.afterRender',hook)

    def test_original_control_cannot_pass_from_vanilla_far_fog(self):
        d={'NORTH-32-60-False':{'frames':[{'patches':[{'median':9}]}]}}
        self.assertFalse(shader_test.reproduces_distortion(d))
        d['NORTH-8-0-False']={'frames':[{'patches':[{'median':9}]}]}
        self.assertTrue(shader_test.reproduces_distortion(d))

    def test_identical_run_control_rejects_temporal_background_drift(self):
        stable=dict(ring_signed=[0.]*4,ring_absolute=[0.]*4,alpha_p95=[0.]*4)
        shader_test.validate_stability(stable)
        for key,drift in (('ring_signed',.16),('ring_absolute',2.),('alpha_p95',9.)):
            changed=copy.deepcopy(stable);changed[key][2]=drift
            with self.assertRaisesRegex(ValueError,'unstable paired'):
                shader_test.validate_stability(changed)
        changed=copy.deepcopy(stable);changed['ring_signed'][0]=float('nan')
        with self.assertRaises(ValueError):shader_test.validate_stability(changed)

    def test_deterministic_profile_keeps_default_temporal_aa_and_only_toggles_bloom(self):
        a=shader_test.pack_options('fixed-static');b=shader_test.pack_options('bloom-off')
        self.assertEqual(['BLOOM_ENABLED'],[k for k in a if a[k]!=b[k]])
        self.assertEqual('0',a['CLOUD_STYLE_DEFINE'])
        self.assertEqual('0',a['LIGHTSHAFT_QUALI_DEFINE'])
        self.assertEqual('0',a['TAA_DEFINE'])
        self.assertNotIn('TAA_DEFINE',shader_test.pack_options('fixed'))
        self.assertNotIn('TAA_DEFINE',shader_test.pack_options('original'))
        self.assertEqual(a,shader_test.pack_options('fixed-repeat'))

    def test_full_temporal_color_matrix_is_separate_from_static_pair_controls(self):
        self.assertEqual(26,len(shader_test.variant_scenarios('fixed')))
        self.assertEqual(26,len(shader_test.variant_scenarios('original')))
        for variant in shader_test.PAIRED_VARIANTS:
            self.assertEqual({'NORTH-8-0-False','NORTH-alpha'},
                             {s['id'] for s in shader_test.variant_scenarios(variant)})
            self.assertEqual('0',shader_test.pack_options(variant)['TAA_DEFINE'])
        self.assertEqual('fixed-static',shader_test.PAIR_REFERENCE)
        self.assertTrue(all(s.get('backdrop') for s in shader_test.variant_scenarios('fixed-static')))
        self.assertTrue(any(not s.get('backdrop') for s in shader_test.variant_scenarios('fixed')))

    def test_receipt_gate_rejects_missing_targets(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaisesRegex(ValueError,'missing or duplicate'): shader_test.verify_receipts(Path(d),'abc')
    def test_case_matrix_covers_facings_alpha_reload_occlusion(self):
        cases=shader_test.scenarios()
        self.assertEqual(set(shader_test.FACES),{c['facing'] for c in cases if c['occluded']})
        self.assertTrue(any(c.get('alpha') for c in cases));self.assertTrue(any(c.get('reload') for c in cases))
        self.assertEqual(set(shader_test.FACES),{c['facing'] for c in cases if c['angle']==60 and c['occluded']})

if __name__=='__main__':unittest.main()
