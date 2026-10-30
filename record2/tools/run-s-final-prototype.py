"""Isolated S architecture experiment. No production configuration or user databases."""
import argparse
import hashlib
import json
import subprocess
import time
import uuid
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb',required=True)
p.add_argument('--serial',default='emulator-5594')
p.add_argument('--rows',type=int,default=1000)
p.add_argument('--profile',choices=['small','normal','heavy','extreme'],default='small')
p.add_argument('--large',action='store_true')
p.add_argument('--large-note',action='store_true')
p.add_argument('--concurrent',action='store_true')
p.add_argument('--fault',choices=['none','cancel','kill','target-cap','wal','zip-output','corrupt','permission','free-floor'],default='none')
p.add_argument('--saf',action='store_true')
p.add_argument('--sort-check',action='store_true')
p.add_argument('--monitor-check',action='store_true')
p.add_argument('--fragmented',action='store_true')
p.add_argument('--at',choices=['copy','extract-row','zip','verify-read'],default='copy')
p.add_argument('--install',action='store_true')
args=p.parse_args()
root=Path(__file__).resolve().parents[1]
run=uuid.uuid4().hex; token=uuid.uuid4().hex
out=root/'build/s-final/runs'/run;out.mkdir(parents=True)
package='com.paherdin.record2.debug'

def adb(*cmd,timeout=120):
    r=subprocess.run([args.adb,'-s',args.serial,*cmd],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    if r.returncode: raise RuntimeError((cmd,r.stderr))
    return r.stdout.strip()

assert adb('emu','avd','name').splitlines()[0]=='record-api34'
assert adb('shell','getprop','ro.build.version.sdk')=='34'
identity={'runId':run,'token':token,'arguments':vars(args),'fingerprint':adb('shell','getprop','ro.build.fingerprint')}
for suffix,path in [('',root/'app/build/outputs/apk/debug/app-debug.apk'),('.test',root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
    sha=hashlib.sha256(path.read_bytes()).hexdigest()
    if args.install: assert 'Success' in adb('install','-r',str(path))
    installed=adb('shell','pm','path',package+suffix);assert installed.startswith('package:')
    assert adb('shell','sha256sum',installed[8:]).split()[0]==sha
    identity['apk'+suffix]=sha
(out/'identity.json').write_text(json.dumps(identity,indent=2),encoding='utf-8')

def command(method,rid):
    cmd=[args.adb,'-s',args.serial,'shell','am','instrument','-w','-r','-e','class','com.paherdin.record2.backup.prototype.SFinalPrototype#'+method]
    for k,v in {'token':token,'runId':rid,'rows':args.rows,'profile':args.profile,'large':str(args.large).lower(),'largeNote':str(args.large_note).lower(),'concurrent':str(args.concurrent).lower(),'fault':args.fault,'at':args.at,'saf':str(args.saf).lower()}.items(): cmd+=['-e',k,str(v)]
    cmd+=['-e','fragmented',str(args.fragmented).lower()]
    return cmd+[package+'.test/androidx.test.runner.AndroidJUnitRunner']

def execute(method,label,timeout=1200):
    cmd=command(method,run if label!='prepare' else run+'-prepare')
    (out/(label+'-command.json')).write_text(json.dumps(cmd,indent=2),encoding='utf-8')
    r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    (out/(label+'.txt')).write_text(r.stdout+r.stderr,encoding='utf-8')
    return 'OK (1 test)' in r.stdout

print('PREPARE',run,args.rows,args.profile,args.fault,flush=True)
prepared=execute('prepareFixture','prepare')
if not prepared: print('PREPARE FAIL',out,flush=True);raise SystemExit(1)
prepare=json.loads(adb('shell','run-as',package,'cat',f'no_backup/s-final-reports/{run}-prepare.json'))
(out/'prepare.json').write_text(json.dumps(prepare,indent=2),encoding='utf-8')
print('RUN',run,flush=True);start=time.monotonic()
if args.fault=='kill':
    cmd=command('runExperiment',run);(out/'experiment-command.json').write_text(json.dumps(cmd,indent=2),encoding='utf-8')
    proc=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    marker=None
    for _ in range(3000):
        r=subprocess.run([args.adb,'-s',args.serial,'shell','run-as',package,'cat',f'no_backup/g0-s-final-{token}/kill.json'],capture_output=True,text=True,encoding='utf-8')
        if r.returncode==0:
            marker=json.loads(r.stdout);break
        if proc.poll() is not None:break
        time.sleep(.01)
    assert marker is not None,'kill checkpoint not reached'
    assert adb('shell','pidof',package)==str(marker['pid'])
    kill=[args.adb,'-s',args.serial,'shell','am','force-stop',package]
    (out/'kill-command.json').write_text(json.dumps(kill),encoding='utf-8')
    subprocess.run(kill,check=True,capture_output=True)
    (out/'killed.txt').write_text(proc.communicate(timeout=30)[0],encoding='utf-8')
    (out/'kill-marker.json').write_text(json.dumps(marker,indent=2),encoding='utf-8')
    passed=execute('verifyKilled','verify')
else: passed=execute('monitorProbe' if args.monitor_check else 'sortEdges' if args.sort_check else 'runExperiment','experiment')
try: metrics=json.loads(adb('shell','run-as',package,'cat',f'no_backup/s-final-reports/{run}.json'))
except RuntimeError: metrics={}
passed=passed and metrics.get('verdict')=='PASS' and metrics.get('pid')!=prepare['pid']
if args.fault=='kill':passed=passed and metrics.get('pid')!=marker['pid']
summary={'runId':run,'passed':passed,'hostSeconds':round(time.monotonic()-start,3),'metrics':metrics}
(out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False),flush=True)
raise SystemExit(0 if passed else 1)
