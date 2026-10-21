#!/usr/bin/env python3
"""Native standalone development launch; distinct from jar/unit and visual acceptance."""
import argparse, hashlib, json, os, shutil, subprocess, sys, tomllib
from pathlib import Path
from live_join_test import OutputPump, PASS_MARKER, SERVER_READY_MARKERS, prepare_server, prepare_client, popen, stop_tree
TARGETS=('forge-1.12.2','fabric-1.16.5','forge-1.16.5','fabric-26.2','neoforge-26.2')

def command(module, task):
    return [str(module/('gradlew.bat' if os.name=='nt' else 'gradlew')),task,
            '--no-daemon','--console=plain','--max-workers=2','-Dorg.gradle.jvmargs=-Xmx1024m']

def client_command(module):
    cmd=command(module,'runLiveJoinTestClient')
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
    module=root/target;evidence=root/'build/requested-runtime'/target;evidence.mkdir(parents=True,exist_ok=True)
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
        client=popen(client_command(module),module);client_output=OutputPump(client,target+'/client')
        if client_output.wait_for((PASS_MARKER,),timeout) is None:
            raise RuntimeError(target+': client did not complete a real join and UI smoke phase')
        try:code=client.wait(timeout=60)
        except subprocess.TimeoutExpired as failure:raise RuntimeError('Client passed but did not stop') from failure
        if code!=0:raise RuntimeError(f'Client exited {code} after marker')
        passed=True
    finally:
        if client is not None:stop_tree(client)
        stop_tree(server,graceful_server=True)
        for side,pump in [('server',server_output),('client',client_output)]:
            if pump is not None:
                pump.thread.join(timeout=15)
                (evidence/(side+'.log')).write_text(''.join(pump.history))
                if pump.thread.is_alive():passed=False
    if not passed:raise RuntimeError('Native acceptance did not complete cleanly')
    jars=[p for p in (module/'build/libs').glob('simply_screens-*.jar') if not p.name.endswith(('-sources.jar','-dev.jar'))]
    if len(jars)!=1:raise RuntimeError('Expected one separately verified production artifact')
    data={'target':target,'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
          'scope':'native development dedicated-server join and OpenUI tab/resize smoke',
          'productionJarSha256':hashlib.sha256(jars[0].read_bytes()).hexdigest(),
          'serverReady':True,'clientJoinAndUiPassed':True,'clientExit':0,
          'notEstablished':['production-jar launch','framebuffer image correctness','shader compatibility','Vulkan/OpenGL parity','live media reload/transparency/occlusion']}
    receipt.write_text(json.dumps(data,indent=2)+'\n')
    print(target+': real native join and OpenUI smoke PASS; framebuffer/media acceptance remains separate')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--target',choices=TARGETS,required=True);parser.add_argument('--timeout',type=int,default=420)
    args=parser.parse_args()
    try:run(Path(__file__).resolve().parents[1],args.target,args.timeout)
    except (RuntimeError,subprocess.CalledProcessError) as error:print(str(error),file=sys.stderr);sys.exit(1)
