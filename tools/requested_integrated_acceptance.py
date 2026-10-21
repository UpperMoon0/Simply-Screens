#!/usr/bin/env python3
"""Separate ordinary Singleplayer menu test, never dedicated/multi-client proof."""
import argparse, hashlib, json, os, shutil, subprocess, sys, time
from pathlib import Path
import requested_live_join as native
from visual_test import checkout_lock
ROOT=Path(__file__).resolve().parents[1]
TARGETS=('fabric-1.16.5','forge-1.16.5')
FINAL='SIMPLYSCREENS_INTEGRATED_CLIENT_PASS javaMajor=8 dedicated=false multiClient=false'
MARKERS=('SIMPLYSCREENS_INTEGRATED_MENU_PASS enabled=true path=singleplayer-create',
         'SIMPLYSCREENS_INTEGRATED_WORLD_PASS server=',
         'SIMPLYSCREENS_INTEGRATED_UI_PASS tabs=true resize=true',FINAL)

def allowed_boundary(data,target,head):
    return (isinstance(data,dict) and target in TARGETS and data.get('target')==target and
            data.get('head')==head and data.get('scope')=='dedicated-server' and
            data.get('result')=='blocked-native-multiplayer' and
            data.get('nativeMultiplayerAllowed') is False and data.get('dedicatedQualified') is False)

def boundary(root,target,head):
    try:data=json.loads((root/'build/requested-runtime'/target/'native-refusal.json').read_text())
    except (OSError,ValueError):return False
    return allowed_boundary(data,target,head)

def command(root,target,directory):
    if target not in TARGETS:raise ValueError('Unsupported integrated target')
    base=(root/target/'run/integrated-acceptance').resolve();directory=directory.resolve()
    if directory==base or base not in directory.parents:raise ValueError('Integrated run escaped isolated parent')
    return native.command(root/target,'runIntegratedAcceptance')+['-PintegratedRunDir='+str(directory)]

def display_command(cmd):
    if os.name=='nt' or os.environ.get('DISPLAY'):return cmd
    xvfb=shutil.which('xvfb-run')
    if not xvfb:raise RuntimeError('Display required for ordinary Singleplayer menu')
    return [xvfb,'-a',*cmd]

def run(root,target,timeout,head):
    if not boundary(root,target,head):raise RuntimeError('No exact-head native refusal; integrated alternative not entered')
    directory=root/target/'run/integrated-acceptance'/('run-'+str(time.time_ns()))
    directory.mkdir(parents=True,exist_ok=False)
    (directory/'options.txt').write_text('pauseOnLostFocus:false\nrenderDistance:3\nmaxFps:30\nguiScale:2\nnarrator:0\n')
    receipt={'target':target,'head':head,'scope':'native integrated-singleplayer development',
             'dedicatedQualified':False,'multiClientQualified':False,'result':'not-completed',
             'notEstablished':['production-jar launch','dedicated-server join','multi-client networking',
                               'framebuffer image correctness','media reload/transparency/occlusion']}
    process=None;output=None;passed=False
    try:
        process=native.popen(display_command(command(root,target,directory)),root/target)
        output=native.OutputPump(process,target+'/integrated')
        if output.wait_for((FINAL,),timeout,line_check=native.reject_probe_failure) is None:
            raise RuntimeError('Native integrated client did not complete normal-menu/UI/media phases')
        try:code=process.wait(timeout=60)
        except subprocess.TimeoutExpired as failure:raise RuntimeError('Integrated client passed but did not exit') from failure
        if code!=0:raise RuntimeError('Integrated client exited nonzero: '+str(code))
        passed=True
    finally:
        cleanup_ok=False
        try:
            native.stop_outputs(directory,[('integrated',process,output,False)])
            cleanup_ok=True
        finally:
            # No successful receipt is written until all drained evidence validates below.
            (directory/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
        if not cleanup_ok:passed=False
    if not passed or output is None:raise RuntimeError('Integrated client did not complete cleanly')
    for marker in MARKERS:
        if sum(marker in line for line in output.history)!=1:raise RuntimeError('Missing/duplicate integrated evidence: '+marker)
    receipt['media']=native.media_evidence(output.history,output.history)
    jars=[p for p in (root/target/'build/libs').glob('simply_screens-*.jar') if not p.name.endswith(('-sources.jar','-dev.jar'))]
    if len(jars)!=1:raise RuntimeError('Expected one separately verified production artifact')
    receipt['productionJarSha256']=hashlib.sha256(jars[0].read_bytes()).hexdigest()
    receipt.update(result='passed',clientExit=0)
    (directory/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print('SIMPLYSCREENS_INTEGRATED_ACCEPTANCE_PASS dedicated=false multiClient=false framebufferPixels=not_tested')

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--target',choices=TARGETS,required=True)
    parser.add_argument('--timeout',type=int,default=900);parser.add_argument('--check-boundary',action='store_true')
    parser.add_argument('--github-output',type=Path);args=parser.parse_args(argv)
    head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    if args.check_boundary:
        allowed=boundary(ROOT,args.target,head)
        if args.github_output:
            with args.github_output.open('a') as output:output.write('accepted='+str(allowed).lower()+'\n')
        print('Separate integrated alternative allowed by exact native refusal: '+str(allowed).lower());return 0
    with checkout_lock(ROOT):run(ROOT,args.target,args.timeout,head)
    return 0
if __name__=='__main__':
    try:raise SystemExit(main())
    except (RuntimeError,ValueError,subprocess.CalledProcessError) as error:print(str(error),file=sys.stderr);raise SystemExit(1)
