"""Bounded sequential API34 S prototype matrix; failures are retained, never retried here."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb',required=True)
p.add_argument('--group',choices=['scale','faults'],required=True)
args=p.parse_args()
root=Path(__file__).resolve().parents[1]
out=root/'build/s-final'/('matrix-'+args.group+'-'+str(time.time_ns()))
out.mkdir(parents=True)
base=[sys.executable,str(root/'tools/run-s-final-prototype.py'),'--adb',args.adb]
if args.group=='scale':
    cases=[('small-saf',['--rows','1000','--profile','small','--saf','--install']),
           ('normal30k',['--rows','30000','--profile','normal']),
           ('middle50k',['--rows','50000','--profile','normal','--concurrent']),
           ('heavy150k',['--rows','150000','--profile','heavy']),
           ('extreme500k',['--rows','500000','--profile','extreme','--large']),
           ('long-note',['--rows','1000','--profile','small','--large-note']),
           ('sort-edges',['--rows','1000','--profile','small','--sort-check'])]
else:
    cases=[(fault+'-'+at,['--rows','50000' if at=='copy' else '1000','--profile','normal' if at=='copy' else 'small','--fault',fault,'--at',at])
           for fault in ['cancel','kill'] for at in ['copy','extract-row','zip','verify-read']]
    cases += [(fault,['--rows','50000' if fault in ['target-cap','wal','free-floor'] else '1000','--profile','normal' if fault in ['target-cap','wal','free-floor'] else 'small','--fault',fault]) for fault in ['target-cap','wal','free-floor','zip-output','corrupt','permission']]
receipts=[]
for label,flags in cases:
    cmd=base+flags
    (out/(label+'-command.json')).write_text(json.dumps(cmd,indent=2),encoding='utf-8')
    print('START',label,flush=True)
    start=time.monotonic()
    with (out/(label+'.txt')).open('w',encoding='utf-8') as f:
        result=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT)
    receipts.append({'label':label,'exit':result.returncode,'hostSeconds':round(time.monotonic()-start,3),'log':label+'.txt'})
    (out/'matrix.json').write_text(json.dumps(receipts,indent=2),encoding='utf-8')
    print('END',label,result.returncode,receipts[-1]['hostSeconds'],flush=True)
print('MATRIX',str(out),flush=True)
raise SystemExit(0 if all(r['exit']==0 for r in receipts) else 1)
