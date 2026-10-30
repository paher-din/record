"""Read-only artifact/source cost audit. AAB bytes are not a device download/install size."""
import hashlib
import json
from pathlib import Path
import zipfile

root=Path(__file__).resolve().parents[1]
out=root/'build/lightweight-backup'
out.mkdir(parents=True,exist_ok=True)

def inspect(path):
    data={'path':str(path.relative_to(root)),'bytes':path.stat().st_size,
          'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    with zipfile.ZipFile(path) as z:
        entries=z.infolist()
        data['dexBytes']=sum(e.file_size for e in entries if e.filename.endswith('.dex'))
        data['dexZipBytes']=sum(e.compress_size for e in entries if e.filename.endswith('.dex'))
        data['native']=[{'name':e.filename,'rawBytes':e.file_size,'zipBytes':e.compress_size}
                        for e in entries if e.filename.endswith('.so')]
        data['assetsRawBytes']=sum(e.file_size for e in entries if '/assets/' in '/'+e.filename)
    return data

artifacts=[inspect(path) for prefix in ['app/build/outputs','size-probe/build/outputs']
           for path in sorted((root/prefix).rglob('*')) if path.suffix in ('.apk','.aab')]
source=root/'app/src/main/kotlin/com/paherdin/record2/backup'
counts={p.name:{'physicalLines':len(p.read_text(encoding='utf-8').splitlines()),'bytes':p.stat().st_size}
        for p in sorted(source.glob('*.kt'))}
schema=json.loads((root/'app/schemas/com.paherdin.record2.storage.RecordDatabase/1.json').read_text())
result={'artifacts':artifacts,'productionBackupSource':counts,
        'productionBackupTotalLines':sum(x['physicalLines'] for x in counts.values()),
        'schemaVersion':schema['database']['version'],'entityCount':len(schema['database']['entities']),
        'warning':'Paired size-probe delta uses an empty app with identical variant/minify/ABI settings; not an exact production release dependency delta.'}
(out/'artifact-cost-audit.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
for a in artifacts:
    print(a['path'],a['bytes'],'DEX',a['dexBytes'],'native',sum(n['rawBytes'] for n in a['native']))
print('backup source lines:',result['productionBackupTotalLines'],'entities:',result['entityCount'])
