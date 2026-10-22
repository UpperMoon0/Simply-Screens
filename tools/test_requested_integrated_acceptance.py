import json, os, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
import requested_integrated_acceptance as integrated
import requested_live_join as native
from test_requested_live_join import Process, Thread
SESSION='00000000-0000-0000-0000-000000000001'
IMAGE='00000000-0000-0000-0000-000000000002'
SERVER='SIMPLYSCREENS_LEGACY_MEDIA_SERVER_PASS session='+SESSION+' image='+IMAGE+' position=fixture uploadStored=true ownership=true\n'
CLIENT='SIMPLYSCREENS_LEGACY_MEDIA_CLIENT_PASS session='+SESSION+' image='+IMAGE+' download=true decodedPixels=true registeredTexture=true geometrySubmitted=true ownedCleanup=true framebufferPixels=not_tested\n'
class Pump:
    def __init__(self,lines):self.history=lines;self.thread=Thread()
    def wait_for(self,markers,timeout,line_check=None):
        for line in self.history:
            if line_check:line_check('integrated',line)
        return next((line for line in self.history if integrated.FINAL in line),None)
class IntegratedAcceptanceTest(unittest.TestCase):
    def boundary(self):return {'target':'fabric-1.16.5','head':'head','scope':'dedicated-server','result':'blocked-native-multiplayer','nativeMultiplayerAllowed':False,'dedicatedQualified':False}
    def test_only_exact_native_refusal_allows_separate_test(self):
        data=self.boundary();self.assertTrue(integrated.allowed_boundary(data,'fabric-1.16.5','head'))
        for key,value in [('target','forge-1.16.5'),('head','old'),('scope','integrated'),('result','failure'),('nativeMultiplayerAllowed',True),('dedicatedQualified',True)]:
            changed=dict(data);changed[key]=value;self.assertFalse(integrated.allowed_boundary(changed,'fabric-1.16.5','head'))
        self.assertFalse(integrated.allowed_boundary(data,'fabric-26.2','head'))
    def test_absent_malformed_and_stale_receipts_do_not_enter(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);path=root/'build/requested-runtime/fabric-1.16.5/native-refusal.json';path.parent.mkdir(parents=True)
            for value in (None,'invalid',json.dumps(dict(self.boundary(),head='stale'))):
                if value is not None:path.write_text(value)
                self.assertFalse(integrated.boundary(root,'fabric-1.16.5','head'))
                with patch.object(native,'popen') as start:
                    with self.assertRaisesRegex(RuntimeError,'No exact-head'):integrated.run(root,'fabric-1.16.5',1,'head')
                    start.assert_not_called()
    def test_command_isolated_native_run_has_no_dedicated_arguments(self):
        root=Path('/project');directory=root/'fabric-1.16.5/run/integrated-acceptance/run-1'
        cmd=integrated.command(root,'fabric-1.16.5',directory)
        self.assertIn('runIntegratedAcceptance',cmd);self.assertIn('-PintegratedRunDir='+str(directory),cmd)
        for forbidden in ('--server','--port','--accessToken','--disableMultiplayer'):self.assertNotIn(forbidden,cmd)
        with self.assertRaises(ValueError):integrated.command(root,'fabric-1.16.5',root/'elsewhere')
        with self.assertRaises(ValueError):integrated.command(root,'forge-1.12.2',directory)
    def test_missing_native_display_fails_closed(self):
        with patch.dict(os.environ,{'DISPLAY':''}),patch.object(integrated.shutil,'which',return_value=None):
            if os.name!='nt':
                with self.assertRaisesRegex(RuntimeError,'Display required'):integrated.display_command(['game'])
    def fixture(self,root,lines=None,code=0,cleanup_error=None):
        target='fabric-1.16.5';folder=root/target/'build/libs';folder.mkdir(parents=True)
        (folder/'simply_screens-fixture.jar').write_bytes(b'jar fixture')
        refusal=root/'build/requested-runtime'/target/'native-refusal.json';refusal.parent.mkdir(parents=True);refusal.write_text(json.dumps(self.boundary()))
        evidence=lines if lines is not None else [x+'\n' for x in integrated.MARKERS]+[SERVER,CLIENT]
        with patch.object(native,'popen',return_value=Process(code)),patch.object(native,'OutputPump',return_value=Pump(evidence)),patch.object(native,'stop_tree',side_effect=cleanup_error),patch.dict(os.environ,{'DISPLAY':':fixture'}),patch('builtins.print'):
            integrated.run(root,target,1,'head')
        return next((root/target/'run/integrated-acceptance').glob('*/receipt.json'))
    def test_complete_drained_native_evidence_is_separate_not_dedicated_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            data=json.loads(self.fixture(Path(tmp)).read_text())
            self.assertEqual('passed',data['result']);self.assertEqual(0,data['clientExit'])
            self.assertIs(False,data['dedicatedQualified']);self.assertIs(False,data['multiClientQualified'])
            self.assertEqual(IMAGE,data['media']['image']);self.assertIs(False,data['media']['framebufferPixelsTested'])
    def test_missing_control_media_or_nonzero_exit_never_passes(self):
        full=[x+'\n' for x in integrated.MARKERS]+[SERVER,CLIENT]
        for lines,code in [(full[1:],0),(full[:-1],0),(full,7),(full+[CLIENT],0),(full+['SIMPLYSCREENS_LEGACY_MEDIA_FAIL late'],0)]:
            with tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp)
                with self.assertRaises(RuntimeError):self.fixture(root,lines,code)
                receipts=list((root/'fabric-1.16.5/run/integrated-acceptance').glob('*/receipt.json'))
                self.assertEqual(1,len(receipts));self.assertEqual('not-completed',json.loads(receipts[0].read_text())['result'])
    def test_cleanup_failure_never_writes_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            with self.assertRaisesRegex(RuntimeError,'cleanup/output failed'):self.fixture(root,cleanup_error=OSError('fixture'))
            receipt=next((root/'fabric-1.16.5/run/integrated-acceptance').glob('*/receipt.json'))
            self.assertEqual('not-completed',json.loads(receipt.read_text())['result'])
    def test_media_requires_matching_server_client_identity_and_all_gates(self):
        self.assertEqual(IMAGE,native.media_evidence([SERVER],[CLIENT])['image'])
        for server,client in [([], [CLIENT]),([SERVER],[CLIENT,CLIENT]),([SERVER],[CLIENT.replace(IMAGE,SESSION)]),([SERVER],[CLIENT.replace('geometrySubmitted=true','geometrySubmitted=false')])]:
            with self.assertRaises(RuntimeError):native.media_evidence(server,client)
    def test_ci_keeps_failed_dedicated_job_and_exact_boundary_gate(self):
        root=Path(__file__).resolve().parents[1];workflow=(root/'.github/workflows/requested-generations.yml').read_text()
        self.assertIn("steps.native_join.outcome == 'failure'",workflow)
        self.assertIn("steps.integrated_boundary.outputs.accepted == 'true'",workflow)
        self.assertIn('--check-boundary --github-output "$GITHUB_OUTPUT"',workflow)
        self.assertNotIn('continue-on-error',workflow)
        for target in integrated.TARGETS:
            build=(root/target/'build.gradle').read_text().split('integratedAcceptance {',1)[1]
            self.assertIn("property 'simplyscreens.integratedAcceptance','true'",build)
            self.assertIn("property 'simplyscreens.mediaSmokeTest','true'",build)
            self.assertNotIn('--server',build);self.assertNotIn('--port',build)
if __name__=='__main__':unittest.main()
