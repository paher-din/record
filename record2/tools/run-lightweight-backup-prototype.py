"""Fresh-process platform-ZIP experiment; production G1 and formal limits are unchanged."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb',required=True)
p.add_argument('--serial',default='emulator-5594')
p.add_argument('--route',choices=['system','unified'],default='system')
p.add_argument('--rows',type=int,default=1000)
p.add_argument('--large',action='store_true')
p.add_argument('--fault',choices=['cancel','space','kill'])
p.add_argument('--install',action='store_true')
p.add_argument('--packing',choices=['size-first','stream','level0'],default='size-first')
p.add_argument('--high-ratio',action='store_true')
p.add_argument('--reader-check',action='store_true')
args=p.parse_args()
root=Path(__file__).resolve().parents[1]
run=uuid.uuid4().hex
out=root/'build/lightweight-backup/runs'/run
out.mkdir(parents=True)
package='com.paherdin.record2.debug'
runner=package+'.test/androidx.test.runner.AndroidJUnitRunner'
route='vacuum-system' if args.route=='system' else 'native-unified'

def adb(*cmd,timeout=120):
    r=subprocess.run([args.adb,'-s',args.serial,*cmd],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    if r.returncode: raise RuntimeError((cmd,r.stderr))
    return r.stdout.strip()

assert adb('emu','avd','name').splitlines()[0]=='record-api34'
assert adb('shell','getprop','ro.build.version.sdk')=='34'
assert adb('shell','getprop','sys.boot_completed')=='1'
identity={'runId':run,'route':args.route,'rows':args.rows,'large':args.large,'fault':args.fault,
          'packing':args.packing,
          'highRatio':args.high_ratio,
          'fingerprint':adb('shell','getprop','ro.build.fingerprint')}
for suffix,path in [('',root/'app/build/outputs/apk/debug/app-debug.apk'),('.test',root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
    sha=hashlib.sha256(path.read_bytes()).hexdigest()
    if args.install: assert 'Success' in adb('install','-r',str(path))
    installed=adb('shell','pm','path',package+suffix)
    assert installed.startswith('package:')
    assert adb('shell','sha256sum',installed[8:]).split()[0]==sha
    identity['apk'+suffix]=sha
(out/'identity.json').write_text(json.dumps(identity,indent=2),encoding='utf-8')

def command(selector,run_id,token=None):
    result=[args.adb,'-s',args.serial,'shell','am','instrument','-w','-r','-e','class',selector,
            '-e','route',route,'-e','rows',str(args.rows),'-e','large',str(args.large).lower(),
            '-e','concurrent',str(not bool(args.fault)).lower(),'-e','runId',run_id]
    result+=['-e','packing',args.packing]
    result+=['-e','highRatio',str(args.high_ratio).lower()]
    if token: result+=['-e','fixtureToken',token]
    return result+[runner]

def execute(cmd,label,timeout=600):
    r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    (out/(label+'.txt')).write_text(r.stdout+r.stderr,encoding='utf-8')
    (out/(label+'-command.json')).write_text(json.dumps(cmd,indent=2),encoding='utf-8')
    return 'OK (1 test)' in r.stdout and 'INSTRUMENTATION_STATUS_CODE: 0' in r.stdout

print('PREPARE',run,args.route,args.rows,args.fault,flush=True)
prepare=command('com.paherdin.record2.backup.prototype.G0SnapshotArchitecturePrototype#prepareFixtureForFreshProcess',run+'-prepare')
assert execute(prepare,'prepare')
meta=json.loads(adb('shell','run-as',package,'cat',f'no_backup/g0-prototype-reports/{run}-prepare.json'))
(out/'prepare.json').write_text(json.dumps(meta,indent=2),encoding='utf-8')
token=meta['fixtureToken']
method={None:'streamingRoundTrip','cancel':'cancellationLeavesSourceUnchanged','space':'spaceFailureLeavesSourceUnchanged','kill':'holdForForceStop'}[args.fault]
if args.reader_check: method='managedReaderRejectsWrites'
cmd=command('com.paherdin.record2.backup.prototype.LightweightZipPrototype#'+method,run,token)
start=time.monotonic()
print('RUN',run,flush=True)
if args.fault=='kill':
    proc=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    marker=None
    for _ in range(300):
        r=subprocess.run([args.adb,'-s',args.serial,'shell','run-as',package,'cat',f'no_backup/g0-export-prototype-{token}/kill-marker.json'],capture_output=True,text=True,encoding='utf-8')
        if r.returncode==0:
            marker=json.loads(r.stdout);break
        if proc.poll() is not None: break
        time.sleep(.2)
    assert marker is not None,'kill checkpoint was not reached'
    assert adb('shell','pidof',package)==str(marker['pid'])
    adb('shell','am','force-stop',package)
    killed_output=proc.communicate(timeout=30)[0]
    (out/'killed.txt').write_text(killed_output,encoding='utf-8')
    (out/'kill-marker.json').write_text(json.dumps(marker,indent=2),encoding='utf-8')
    verify=command('com.paherdin.record2.backup.prototype.LightweightZipPrototype#verifyAfterForceStop',run,token)
    passed=execute(verify,'verify-after-kill')
else:
    passed=execute(cmd,'experiment')
try:
    metrics=json.loads(adb('shell','run-as',package,'cat',f'no_backup/lightweight-reports/{run}.json'))
except RuntimeError:
    metrics={}
passed=passed and bool(metrics) and metrics.get('pid')!=meta['preparePid']
if args.fault=='kill': passed=passed and metrics.get('pid')!=marker['pid']
summary={'runId':run,'passed':passed,'seconds':round(time.monotonic()-start,3),'metrics':metrics}
(out/'summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False),flush=True)
sys.exit(0 if passed else 1)
