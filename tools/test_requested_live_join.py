import io, json, os, queue, subprocess, sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import requested_live_join as live
class Process:
    def __init__(self,code=0,exited=False):self.code=code;self.exited=exited;self.stdin=io.StringIO()
    def wait(self,timeout=None):self.exited=True;return self.code
    def poll(self):return self.code if self.exited else None
class Thread:
    def join(self,timeout=None):pass
    def is_alive(self):return False
class Pump:
    def __init__(self,marker,prefix=''):self.marker=marker;self.prefix=prefix;self.history=['fixture log\n'];self.thread=Thread()
    def wait_for(self,markers,timeout,line_check=None):
        if line_check is not None and self.marker is not None:line_check(self.prefix,self.marker)
        return self.marker
class RequestedLiveJoinTest(unittest.TestCase):
    def test_placed_screen_receipt_binds_native_model_marker_and_exact_session_png(self):
        session='12345678-1234-1234-1234-123456789abc'
        name='simplyscreens-legacy-media-'+session+'.png'
        marker='SIMPLYSCREENS_LEGACY_PLACED_MODEL_PASS modelNonMissing=true spritesNonMissing=true screenshot='+name
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);module=root/'forge-1.12.2';evidence=root/'evidence';evidence.mkdir()
            source=module/'run/live-join/client/screenshots'/name;source.parent.mkdir(parents=True)
            data=b'\x89PNG\r\n\x1a\n'+(13).to_bytes(4,'big')+b'IHDR'+(854).to_bytes(4,'big')+(480).to_bytes(4,'big')
            source.write_bytes(data)
            result=live.placed_model_evidence(module,evidence,[marker],session)
            self.assertEqual(data,(evidence/'placed-screen.png').read_bytes())
            self.assertEqual(live.hashlib.sha256(data).hexdigest(),result['screenshotSha256'])
            self.assertFalse(result['framebufferPixelsTested'])
            for lines in ([],[marker,marker],[marker.replace(session,'another-session')],[marker+' trailing text']):
                with self.assertRaises(RuntimeError):live.placed_model_evidence(module,evidence,lines,session)
            source.write_bytes(b'invalid image bytes'*2)
            with self.assertRaises(RuntimeError):live.placed_model_evidence(module,evidence,[marker],session)
            source.unlink()
            with self.assertRaises(RuntimeError):live.placed_model_evidence(module,evidence,[marker],session)

    def test_observed_native_ineligibility_is_fatal_only_for_exact_loaded_client(self):
        for target,title in [('fabric-1.16.5','net.minecraft.client.gui.screens.TitleScreen'),('forge-1.16.5','net.minecraft.client.gui.screen.MainMenuScreen')]:
            line='[native] Native join probe waiting: screen='+title+', overlay=none, multiplayerAllowed=false'
            with self.assertRaisesRegex(RuntimeError,'native Minecraft multiplayer eligibility is false'):
                live.reject_native_ineligibility(target+'/client',line)
            for role in ('server','other'):live.reject_native_ineligibility(target+'/'+role,line)
            for other in (line.replace('overlay=none','overlay=LoadingOverlay'),line.replace('multiplayerAllowed=false','multiplayerAllowed=true'),line.replace(title,'ConnectScreen'),line+' trailing text',line.replace('Native join probe waiting','OTHER_MARKER')):
                live.reject_native_ineligibility(target+'/client',other)
            for other in ('fabric-26.2/client','neoforge-26.2/client','forge-1.12.2/client','fabric-1.20.1/client'):
                live.reject_native_ineligibility(other,line)
    def test_shared_pump_checks_failure_before_success_without_changing_default(self):
        line=live.PASS_MARKER+' Native join probe waiting: screen=net.minecraft.client.gui.screens.TitleScreen, overlay=none, multiplayerAllowed=false'
        pump=live.OutputPump.__new__(live.OutputPump)
        pump.process=type('StillRunning',(),{'poll':lambda self:None})()
        pump.prefix='fabric-1.16.5/client';pump.lines=queue.Queue();pump.lines.put(line)
        with self.assertRaisesRegex(RuntimeError,'native Minecraft multiplayer eligibility is false'):
            pump.wait_for((live.PASS_MARKER,),1,line_check=live.reject_native_ineligibility)
        pump.lines.put(line)
        self.assertEqual(line,pump.wait_for((live.PASS_MARKER,),1))
    @unittest.skipUnless(os.name=='posix','Owned display command uses a POSIX shell')
    def test_legacy_missing_display_prerequisites_fail_closed(self):
        module=Path('/fixture/forge-1.12.2')
        with patch.dict(os.environ,{'DISPLAY':':fixture'}),patch.object(live.shutil,'which',return_value=None):
            with self.assertRaisesRegex(RuntimeError,'xrandr executable'):live.client_command(module)
        with patch.dict(os.environ,{'DISPLAY':''}),patch.object(live.shutil,'which',side_effect=lambda name:'/usr/bin/xrandr' if name=='xrandr' else None):
            with self.assertRaisesRegex(RuntimeError,'xvfb-run'):live.client_command(module)
    @unittest.skipUnless(os.name=='posix','Owned display command uses a POSIX shell')
    def test_legacy_query_uses_same_owned_display_and_modern_launch_is_unchanged(self):
        with patch.dict(os.environ,{'DISPLAY':''}),patch.object(live.shutil,'which',side_effect=lambda name:'/usr/bin/'+name):
            module=Path('/fixture/forge-1.12.2');launch=live.client_command(module)
            self.assertEqual(['/usr/bin/xvfb-run','-a','sh','-c'],launch[:4])
            self.assertEqual('set -eu; xrandr --query; exec "$@"',launch[4])
            self.assertEqual(live.command(module,'runLiveJoinTestClient'),launch[6:])
            for target in ('fabric-1.16.5','forge-1.16.5','fabric-26.2','neoforge-26.2'):
                module=Path('/fixture')/target
                self.assertEqual(['/usr/bin/xvfb-run','-a']+live.command(module,'runLiveJoinTestClient'),live.client_command(module))
    def test_windows_never_queries_or_wraps_the_native_display(self):
        module=Path('/fixture/forge-1.12.2');cmd=['gradlew.bat','runLiveJoinTestClient']
        with patch.object(live.os,'name','nt'),patch.object(live,'command',return_value=cmd),patch.object(live.shutil,'which') as which:
            self.assertEqual(cmd,live.client_command(module));which.assert_not_called()
    def display_query_fixture(self,query_exit):
        # Shell/argv fixture only, not a display or game acceptance substitute.
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);module=root/'forge-1.12.2'
            query=root/'xrandr'
            query.write_text('#!/bin/sh\n[ "$1" = "--query" ] || exit 99\nprintf "%s" "$DISPLAY" > query-display\nexit '+str(query_exit)+'\n')
            query.chmod(0o755)
            game=[sys.executable,'-c','import json,os,sys;from pathlib import Path;Path("client-args").write_text(json.dumps([os.environ["DISPLAY"],sys.argv[1:]]))','argument with spaces','literal;not-a-command']
            with patch.dict(os.environ,{'DISPLAY':':fixture-only','PATH':str(root)+os.pathsep+os.environ.get('PATH','')}),patch.object(live,'command',return_value=game):
                launch=live.client_command(module)
                self.assertEqual(['sh','-c'],launch[:2])
                result=subprocess.run(launch,cwd=root,timeout=3,check=False)
            self.assertEqual(':fixture-only',(root/'query-display').read_text())
            if query_exit:
                self.assertNotEqual(0,result.returncode);self.assertFalse((root/'client-args').exists())
            else:
                self.assertEqual(0,result.returncode)
                self.assertEqual([':fixture-only',game[3:]],json.loads((root/'client-args').read_text()))
    @unittest.skipUnless(os.name=='posix','Owned display command uses a POSIX shell')
    def test_failed_query_prevents_native_client_execution(self):self.display_query_fixture(1)
    @unittest.skipUnless(os.name=='posix','Owned display command uses a POSIX shell')
    def test_query_preserves_existing_display_and_literal_native_arguments(self):self.display_query_fixture(0)
    def test_official_xrandr_cli_and_debug_are_scoped_without_disable_flag(self):
        root=Path(__file__).resolve().parents[1]
        self.assertIn('x11-xserver-utils',(root/'.github/workflows/requested-generations.yml').read_text())
        build=(root/'forge-1.12.2/build.gradle').read_text()
        client=build.split('liveJoinTestClient {',1)[1].split('liveJoinTestServer {',1)[0]
        self.assertIn("property 'org.lwjgl.util.Debug','true'",client)
        self.assertEqual(1,build.count("property 'org.lwjgl.util.Debug'"))
        self.assertNotIn('LWJGL_DISABLE_XRANDR',build+(root/'tools/requested_live_join.py').read_text())
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
    def run_fixture(self,root,server_ready,client_pass,exit_code=0,target='fabric-26.2',cleanup_error=None,server_code=0,server_exited=False):
        module=root/target;libs=module/'build/libs';libs.mkdir(parents=True)
        (libs/('simply_screens-'+target+'-0.10.1.jar')).write_bytes(b'jar fixture')
        def prepare(module):
            folder=module/'run/live-join/server';folder.mkdir(parents=True,exist_ok=True)
            (folder/'server.properties').write_text('online-mode=false\n')
        with patch('builtins.print'),patch.object(live,'prepare_server',prepare),patch.object(live,'prepare_client'),patch.object(live,'popen',side_effect=[Process(server_code,server_exited),Process(exit_code)]),patch.object(live,'OutputPump',side_effect=[Pump(server_ready,target+'/server'),Pump(client_pass,target+'/client')]),patch.object(live,'stop_tree',side_effect=cleanup_error) as stop,patch.object(live.subprocess,'check_output',return_value='abc123\n'),patch.dict(live.os.environ,{'DISPLAY':':1'}):
            try:live.run(root,target,1)
            finally:self.assertEqual(1 if server_ready is None else 2,stop.call_count)
    def test_native_policy_failure_preserves_cleanup_logs_and_never_writes_pass_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);target='fabric-1.16.5'
            line='Native join probe waiting: screen=net.minecraft.client.gui.screens.TitleScreen, overlay=none, multiplayerAllowed=false'
            with self.assertRaisesRegex(RuntimeError,'native Minecraft multiplayer eligibility is false'):
                self.run_fixture(root,'Done (',line,target=target)
            evidence=root/'build/requested-runtime'/target
            self.assertFalse((evidence/'receipt.json').exists())
            refusal=json.loads((evidence/'native-refusal.json').read_text())
            self.assertEqual('abc123',refusal['head']);self.assertIs(False,refusal['dedicatedQualified'])
            self.assertTrue((evidence/'server.log').exists());self.assertTrue((evidence/'client.log').exists())
    def test_cleanup_failure_cannot_authorize_integrated_alternative(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);target='fabric-1.16.5'
            line='Native join probe waiting: screen=net.minecraft.client.gui.screens.TitleScreen, overlay=none, multiplayerAllowed=false'
            with self.assertRaisesRegex(RuntimeError,'cleanup/output failed'):
                self.run_fixture(root,'Done (',line,target=target,cleanup_error=OSError('cleanup fixture'))
            evidence=root/'build/requested-runtime'/target
            self.assertFalse((evidence/'native-refusal.json').exists())
            self.assertFalse((evidence/'receipt.json').exists())
            self.assertTrue((evidence/'server.log').exists());self.assertTrue((evidence/'client.log').exists())
    def test_late_probe_failure_and_undrained_output_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);pump=Pump(None);pump.history=['SIMPLYSCREENS_LEGACY_MEDIA_FAIL late async completion']
            with patch.object(live,'stop_tree'):
                with self.assertRaisesRegex(RuntimeError,'cleanup/output failed'):live.stop_outputs(root,[('client',Process(),pump,False)])
                pump.history=[];pump.thread=type('Stuck',(),{'join':lambda self,timeout:None,'is_alive':lambda self:True})()
                with self.assertRaisesRegex(RuntimeError,'did not drain'):live.stop_outputs(root,[('client',Process(),pump,False)])
    def test_server_early_death_and_nonzero_graceful_exit_never_pass(self):
        for code,exited in [(7,True),(0,True),(7,False)]:
            with tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp)
                with self.assertRaises(RuntimeError):self.run_fixture(root,'Done (','PASS',server_code=code,server_exited=exited)
                self.assertFalse((root/'build/requested-runtime/fabric-26.2/receipt.json').exists())
    def test_late_game_crash_is_fatal_but_native_warnings_are_not_suppressed(self):
        with tempfile.TemporaryDirectory() as tmp,patch.object(live,'stop_tree'):
            pump=Pump(None)
            for marker in live.CRASH_MARKERS+('SIMPLYSCREENS_LIVE_JOIN_TEST_FAIL watchdog',):
                pump.history=['prior pass marker',marker]
                with self.assertRaisesRegex(RuntimeError,'cleanup/output failed'):live.stop_outputs(Path(tmp),[('client',Process(),pump,False)])
            pump.history=['[WARN] missing narrator library','[ERROR] Unable to initialize OpenAL']
            live.stop_outputs(Path(tmp),[('client',Process(),pump,False)])
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
