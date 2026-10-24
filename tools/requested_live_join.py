#!/usr/bin/env python3
"""Native standalone development launch; distinct from jar/unit and visual acceptance."""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tomllib
from pathlib import Path
from live_join_test import OutputPump, PASS_MARKER, SERVER_READY_MARKERS, prepare_server, prepare_client, popen, stop_tree
TARGETS=('forge-1.12.2','fabric-1.16.5','forge-1.16.5','fabric-26.2','neoforge-26.2')
LEGACY_TARGETS=TARGETS[:3]
CRASH_MARKERS=('Minecraft has crashed','---- Minecraft Crash Report ----',
               'This crash report has been saved to','Encountered an unexpected exception',
               'Exception in thread "Client thread"','Exception in thread "Render thread"',
               'Exception in thread "main"','FAILURE: Build failed')

class NativeEligibilityUnavailable(RuntimeError):
    """Observed native refusal, never a successful or skipped dedicated test."""

def reject_probe_failure(prefix,line):
    if any(marker in line for marker in ('SIMPLYSCREENS_LEGACY_MEDIA_FAIL',
            'SIMPLYSCREENS_INTEGRATED_FAIL','SIMPLYSCREENS_LIVE_JOIN_TEST_FAIL',*CRASH_MARKERS)):
        raise RuntimeError(prefix+': '+line.strip())

def stop_outputs(evidence,outputs):
    """Always retire every owned process and drain logs, even after one failure."""
    errors=[]
    for side,process,pump,graceful in outputs:
        if process is None:continue
        try:
            if graceful:
                if process.poll() is not None:raise RuntimeError('server exited before intended shutdown')
                if process.stdin is None:raise RuntimeError('server stop input unavailable')
                process.stdin.write('stop\n');process.stdin.flush()
                code=process.wait(timeout=60)
                if code!=0:raise RuntimeError('server graceful wrapper exited '+str(code))
        except Exception as error:errors.append(side+': '+str(error))
        finally:
            try:stop_tree(process)
            except Exception as error:errors.append(side+': '+str(error))
    for side,process,pump,graceful in outputs:
        if pump is None:continue
        try:
            pump.thread.join(timeout=15)
            (evidence/(side+'.log')).write_text(''.join(pump.history))
            if pump.thread.is_alive():raise RuntimeError('output reader did not drain')
            for line in pump.history:reject_probe_failure(side,line)
        except Exception as error:errors.append(side+': '+str(error))
    if errors:raise RuntimeError('Native acceptance cleanup/output failed: '+'; '.join(errors))

def media_evidence(server_lines,client_lines):
    pattern=r' session=([0-9a-f-]{36}) image=([0-9a-f-]{36}) '
    def identities(lines,marker,fields):
        found=[]
        for line in lines:
            if marker not in line:continue
            match=re.search(re.escape(marker)+pattern,line)
            if not match or not all(field in line for field in fields):raise RuntimeError('Incomplete native media evidence')
            found.append(match.groups())
        if len(found)!=1:raise RuntimeError('Expected exactly one native media evidence marker: '+marker)
        return found[0]
    server=identities(server_lines,'SIMPLYSCREENS_LEGACY_MEDIA_SERVER_PASS',('uploadStored=true','ownership=true'))
    client=identities(client_lines,'SIMPLYSCREENS_LEGACY_MEDIA_CLIENT_PASS',('download=true','decodedPixels=true','registeredTexture=true','geometrySubmitted=true','ownedCleanup=true','framebufferPixels=not_tested'))
    if server!=client:raise RuntimeError('Native server/client media identity mismatch')
    return {'session':server[0],'image':server[1],'uploadStored':True,'downloadDecodedPixels':True,'textureRegistered':True,'geometrySubmitted':True,'ownedCleanup':True,'framebufferPixelsTested':False}

def placed_model_evidence(module, evidence, client_lines, session):
    # A native model/sprite assertion plus a captured frame, not a pixel oracle.
    name='simplyscreens-legacy-media-'+session+'.png'
    marker='SIMPLYSCREENS_LEGACY_PLACED_MODEL_PASS modelNonMissing=true spritesNonMissing=true screenshot='+name
    found=[line.rstrip() for line in client_lines if 'SIMPLYSCREENS_LEGACY_PLACED_MODEL_PASS' in line]
    if len(found)!=1 or not found[0].endswith(marker):raise RuntimeError('Missing or mismatched placed-screen evidence')
    source=module/'run/live-join/client/screenshots'/name
    if source.is_symlink() or not source.is_file() or not 24<=source.stat().st_size<=16*1024*1024:
        raise RuntimeError('Missing or oversized placed-screen screenshot')
    data=source.read_bytes()
    if data[:8]!=b'\x89PNG\r\n\x1a\n' or data[12:16]!=b'IHDR' or not all(int.from_bytes(data[i:i+4],'big')>0 for i in (16,20)):
        raise RuntimeError('Invalid placed-screen PNG evidence')
    destination=evidence/'placed-screen.png';destination.write_bytes(data)
    return {'modelNonMissing':True,'spritesNonMissing':True,'screenshotFile':destination.name,
            'screenshotSha256':hashlib.sha256(data).hexdigest(),'framebufferPixelsTested':False}

def reject_native_ineligibility(prefix,line):
    reject_probe_failure(prefix,line)
    # Read-only native boundary, never an account/connection override or pass.
    titles={'fabric-1.16.5/client':'net.minecraft.client.gui.screens.TitleScreen',
            'forge-1.16.5/client':'net.minecraft.client.gui.screen.MainMenuScreen'}
    title=titles.get(prefix)
    if title and line.rstrip().endswith('Native join probe waiting: screen='+title+', overlay=none, multiplayerAllowed=false'):
        raise NativeEligibilityUnavailable(prefix+': native Minecraft multiplayer eligibility is false after loading; '
                           'no join acceptance is claimed and no connection override is attempted')

def command(module, task):
    return [str(module/('gradlew.bat' if os.name=='nt' else 'gradlew')),task,
            '--no-daemon','--console=plain','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx1024m']

def client_command(module,fixture_url=None):
    cmd=command(module,'runLiveJoinTestClient')
    if fixture_url is not None:
        if module.name not in ('fabric-1.16.5','forge-1.16.5'):
            raise ValueError('Service fixture is restricted to the two 1.16.5 CI clients')
        cmd.append('-PnativeServiceFixture='+fixture_url)
    if os.name=='nt':return cmd
    legacy=module.name=='forge-1.12.2'
    # LWJGL 2 invokes the real xrandr executable. Supply and observe that
    # prerequisite instead of bypassing XRandR or fabricating display modes.
    if legacy and not shutil.which('xrandr'):
        raise RuntimeError('LWJGL 2 requires the xrandr executable (Ubuntu package x11-xserver-utils)')
    prefix=[]
    if not os.environ.get('DISPLAY'):
        xvfb=shutil.which('xvfb-run')
        if not xvfb:raise RuntimeError('A display or xvfb-run is required for an actual client test')
        prefix=[xvfb,'-a']
    if legacy:
        # Same owned display session, fail closed, with literal argv preserved.
        # Shared process-group cleanup supervises the query and native client.
        cmd=['sh','-c','set -eu; xrandr --query; exec "$@"','simplyscreens-1122-display',*cmd]
    return prefix+cmd

def verify_loader_warning_visibility(module):
    # Reused isolated runs may still contain the old diagnostic-only setting.
    # Never silently reuse it or modify a user's existing configuration.
    names={'neoforge-26.2':'neoforge-client.toml','forge-1.16.5':'forge-client.toml'}
    if module.name not in names:return
    path=module/'run/live-join/client/config'/names[module.name]
    if not path.is_file():return
    try:data=tomllib.loads(path.read_text())
    except (OSError,tomllib.TOMLDecodeError) as error:
        raise RuntimeError('Cannot verify native loader warning configuration: '+str(path)) from error
    settings=data.get('client',{}) if module.name=='forge-1.16.5' else data
    if settings.get('showLoadWarnings') is False:
        raise RuntimeError('Loader warnings are suppressed in the existing isolated run configuration: '+str(path))

def run(root,target,timeout):
    evidence=root/'build/requested-runtime'/target;evidence.mkdir(parents=True,exist_ok=True)
    refusal=evidence/'native-refusal.json'
    if refusal.exists():refusal.unlink()
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    try:return run_native(root,target,timeout,head)
    except NativeEligibilityUnavailable:
        # run_native's finally must finish cleanly before this specific exception
        # can reach us. Any teardown fault replaces it with ordinary RuntimeError.
        if target not in ('fabric-1.16.5','forge-1.16.5'):raise
        refusal.write_text(json.dumps({'target':target,'head':head,'scope':'dedicated-server',
            'result':'blocked-native-multiplayer','nativeMultiplayerAllowed':False,'dedicatedQualified':False},indent=2)+'\n')
        raise

def run_native(root,target,timeout,head,fixture_url=None,evidence_dir=None):
    module=root/target;evidence=evidence_dir or root/'build/requested-runtime'/target;evidence.mkdir(parents=True,exist_ok=True)
    receipt=evidence/'receipt.json'
    if receipt.exists():receipt.unlink()
    prepare_server(module);prepare_client(module);verify_loader_warning_visibility(module)
    properties=module/'run/live-join/server/server.properties'
    properties.write_text(properties.read_text()+'server-ip=127.0.0.1\nmax-players=2\n')
    server=popen(command(module,'runLiveJoinTestServer'),module)
    server_output=OutputPump(server,target+'/server');client=None;client_output=None;passed=False
    try:
        if server_output.wait_for(SERVER_READY_MARKERS,timeout) is None:
            raise RuntimeError(target+': native dedicated server did not become ready')
        client=popen(client_command(module,fixture_url),module);client_output=OutputPump(client,target+'/client')
        if client_output.wait_for((PASS_MARKER,),timeout,line_check=reject_native_ineligibility) is None:
            raise RuntimeError(target+': client did not complete a real join and UI smoke phase')
        try:code=client.wait(timeout=60)
        except subprocess.TimeoutExpired as failure:raise RuntimeError('Client passed but did not stop') from failure
        if code!=0:raise RuntimeError(f'Client exited {code} after marker')
        if server.poll() is not None:raise RuntimeError('Native server exited before client verification completed')
        passed=True
    finally:
        stop_outputs(evidence,[('client',client,client_output,False),('server',server,server_output,True)])
    if not passed:raise RuntimeError('Native acceptance did not complete cleanly')
    jars=[p for p in (module/'build/libs').glob('simply_screens-*.jar') if not p.name.endswith(('-sources.jar','-dev.jar'))]
    if len(jars)!=1:raise RuntimeError('Expected one separately verified production artifact')
    data={'target':target,'head':head,
          'scope':'native development dedicated-server join and OpenUI tab/resize smoke',
          'productionJarSha256':hashlib.sha256(jars[0].read_bytes()).hexdigest(),
          'serverReady':True,'clientJoinAndUiPassed':True,'clientExit':0,'serverGracefulExit':0,
          'notEstablished':['production-jar launch','framebuffer image correctness','shader compatibility','Vulkan/OpenGL parity','live media reload/transparency/occlusion']}
    if target in LEGACY_TARGETS:
        data['media']=media_evidence(server_output.history,client_output.history)
        data['scope']+=' and native upload/download/decode/texture/submission smoke'
        if target=='forge-1.12.2':
            data['placedScreen']=placed_model_evidence(module,evidence,client_output.history,data['media']['session'])
    receipt.write_text(json.dumps(data,indent=2)+'\n')
    print(target+': real native join and OpenUI smoke PASS; framebuffer/media acceptance remains separate')

def run_with_service_fixture(root,target,timeout):
    from native_service_fixture import TARGETS as FIXTURE_TARGETS, service_fixture
    if target not in FIXTURE_TARGETS:
        raise ValueError('Service fixture is restricted to the two 1.16.5 CI clients')
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
    evidence=root/'build/requested-runtime'/target
    evidence.mkdir(parents=True,exist_ok=True)
    receipt=evidence/'receipt.json'
    for path in (receipt,evidence/'native-refusal.json'):
        if path.exists():path.unlink()
    denial=evidence/'service-denied'
    try:
        with service_fixture(False) as (url,requests):
            try:run_native(root,target,timeout,head,url,denial)
            except NativeEligibilityUnavailable:pass
            else:raise RuntimeError('Native client did not reject the denied service fixture')
            if '/privileges' not in requests:
                raise RuntimeError('Denial control did not consume the service fixture')
            logs=''.join((denial/(side+'.log')).read_text() for side in ('client','server'))
            if any(marker in logs for marker in (PASS_MARKER,'ScreensProbe joined the game',
                    'SIMPLYSCREENS_LEGACY_MEDIA_SERVER_PASS','SIMPLYSCREENS_LEGACY_MEDIA_CLIENT_PASS')):
                raise RuntimeError('Denied service fixture unexpectedly entered gameplay')
        print(target+': native service denial control PASS; no dedicated join')
        with service_fixture(True) as (url,requests):
            run_native(root,target,timeout,head,url)
            if '/privileges' not in requests:
                raise RuntimeError('Join did not consume the allowed service fixture')
        data=json.loads(receipt.read_text())
        data['authentication']={'mode':'loopback-service-fixture','realAccountQualified':False,
                                'nativeDenialControlPassed':True,'nativeAllowedJoinPassed':True}
        data['notEstablished'].append('real-account authentication and service eligibility')
        receipt.write_text(json.dumps(data,indent=2)+'\n')
    except BaseException:
        if receipt.exists():receipt.unlink()
        raise

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--target',choices=TARGETS,required=True);parser.add_argument('--timeout',type=int,default=420)
    parser.add_argument('--service-fixture',action='store_true',help='1.16.5 only: test native denial and join with a loopback service fixture; no real-account qualification')
    args=parser.parse_args()
    try:
        runner=run_with_service_fixture if args.service_fixture else run
        runner(Path(__file__).resolve().parents[1],args.target,args.timeout)
    except (RuntimeError,subprocess.CalledProcessError) as error:print(str(error),file=sys.stderr);sys.exit(1)
