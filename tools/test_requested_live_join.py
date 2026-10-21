import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import requested_live_join as live
class Process:
    def __init__(self,code=0):self.code=code
    def wait(self,timeout=None):return self.code
class Thread:
    def join(self,timeout=None):pass
    def is_alive(self):return False
class Pump:
    def __init__(self,marker):self.marker=marker;self.history=['fixture log\n'];self.thread=Thread()
    def wait_for(self,markers,timeout):return self.marker
class RequestedLiveJoinTest(unittest.TestCase):
    def test_commands_use_native_project_wrapper_and_bounded_workers(self):
        command=live.command(Path('/project/fabric-26.2'),'runLiveJoinTestServer')
        self.assertEqual('/project/fabric-26.2/gradlew',command[0]);self.assertIn('--max-workers=2',command)
        self.assertNotIn(':fabric-26.2:runLiveJoinTestServer',command)
    def test_native_acceptance_rejects_stale_warning_suppression_without_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            for target,name,prefix in [('neoforge-26.2','neoforge-client.toml',''),('forge-1.16.5','forge-client.toml','[client]\n')]:
                module=Path(tmp)/target
                live.verify_loader_warning_visibility(module)
                path=module/'run/live-join/client/config'/name
                path.parent.mkdir(parents=True)
                stale=prefix+'showLoadWarnings = false # old isolated diagnostic\n'
                path.write_text(stale)
                with self.assertRaisesRegex(RuntimeError,'warnings are suppressed'):
                    live.verify_loader_warning_visibility(module)
                self.assertEqual(stale,path.read_text())
                path.write_text(prefix+'showLoadWarnings = true\n')
                live.verify_loader_warning_visibility(module)
                path.write_text('malformed [toml')
                with self.assertRaisesRegex(RuntimeError,'Cannot verify'):
                    live.verify_loader_warning_visibility(module)
    def run_fixture(self,root,server_ready,client_pass,exit_code=0):
        module=root/'fabric-26.2';libs=module/'build/libs';libs.mkdir(parents=True)
        (libs/'simply_screens-fabric-26.2-0.10.0.jar').write_bytes(b'jar fixture')
        def prepare(module):
            folder=module/'run/live-join/server';folder.mkdir(parents=True,exist_ok=True)
            (folder/'server.properties').write_text('online-mode=false\n')
        with patch('builtins.print'),patch.object(live,'prepare_server',prepare),patch.object(live,'prepare_client'),patch.object(live,'popen',side_effect=[Process(),Process(exit_code)]),patch.object(live,'OutputPump',side_effect=[Pump(server_ready),Pump(client_pass)]),patch.object(live,'stop_tree'),patch.object(live.subprocess,'check_output',return_value='abc123\n'),patch.dict(live.os.environ,{'DISPLAY':':1'}):
            live.run(root,'fabric-26.2',1)
    def test_missing_server_or_ui_success_never_writes_receipt(self):
        for ready,marker,code in [(None,'PASS',0),('Done (',None,0),('Done (','PASS',1)]:
            with tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp)
                with self.assertRaises(RuntimeError):self.run_fixture(root,ready,marker,code)
                self.assertFalse((root/'build/requested-runtime/fabric-26.2/receipt.json').exists())
                self.assertTrue((root/'build/requested-runtime/fabric-26.2/server.log').exists())
    def test_receipt_records_exact_scope_and_unproven_visual_properties(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);self.run_fixture(root,'Done (','PASS')
            data=json.loads((root/'build/requested-runtime/fabric-26.2/receipt.json').read_text())
            self.assertEqual('abc123',data['head']);self.assertTrue(data['clientJoinAndUiPassed'])
            self.assertIn('production-jar launch',data['notEstablished'])
            self.assertIn('Vulkan/OpenGL parity',data['notEstablished'])
            self.assertIn('server-ip=127.0.0.1',(root/'fabric-26.2/run/live-join/server/server.properties').read_text())
if __name__=='__main__':unittest.main()
