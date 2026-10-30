"""Download official public artifacts into build; statically identify all ABI engines."""
import hashlib
import io
import json
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

out=Path(__file__).resolve().parents[1]/'build/s-final/official'
out.mkdir(parents=True,exist_ok=True)
sources={
    'metadata.xml':'https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-android/maven-metadata.xml',
    'vacuum-3.39.2.c':'https://raw.githubusercontent.com/sqlite/sqlite/version-3.39.2/src/vacuum.c',
    'aosp-sqlite-connection.cpp':'https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/jni/android_database_SQLiteConnection.cpp',
    'aosp-sqlite-pool.java':'https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteConnectionPool.java',
    'aosp-sqlite-database.java':'https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteDatabase.java',
    'aosp-sqlite-java-connection.java':'https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteConnection.java',
    'aosp-default-error-handler.java':'https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-14.0.0_r1/core/java/android/database/DefaultDatabaseErrorHandler.java',
    'sqlite-wal.html':'https://sqlite.org/wal.html',
    'sqlite-3.51.3.html':'https://sqlite.org/releaselog/3_51_3.html',
}
receipts=[]
for name,url in sources.items():
    data=urllib.request.urlopen(url,timeout=60).read();(out/name).write_bytes(data)
    receipts.append({'name':name,'url':url,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
versions=[v.text for v in ET.fromstring((out/'metadata.xml').read_bytes()).findall('.//version')]
stable=sorted([v for v in versions if re.fullmatch(r'\d+\.\d+\.\d+',v)],key=lambda v:tuple(map(int,v.split('.'))))
version=stable[-1]
url=f'https://dl.google.com/dl/android/maven2/androidx/sqlite/sqlite-bundled-android/{version}/sqlite-bundled-android-{version}.aar'
data=urllib.request.urlopen(url,timeout=60).read();(out/f'sqlite-bundled-{version}.aar').write_bytes(data)
artifact={'version':version,'url':url,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'abis':[]}
with zipfile.ZipFile(io.BytesIO(data)) as z:
    for n in z.namelist():
        if not n.endswith('.so'):continue
        elf=z.read(n)
        ids=sorted(set(x.decode() for x in re.findall(rb'20\d\d-\d\d-\d\d \d\d:\d\d:\d\d [0-9a-f]{64}',elf)))
        ver=sorted(set(x.decode() for x in re.findall(rb'(?<![0-9])3\.\d{1,2}\.\d{1,2}\x00',elf)))
        artifact['abis'].append({'path':n,'bytes':len(elf),'sha256':hashlib.sha256(elf).hexdigest(),'sourceIds':ids,'versionStrings':[v.rstrip('\0') for v in ver]})
result={'warning':'Static artifact identity, not runtime or patch absence proof; no dependency installed','availableVersions':versions,'latestStable':version,'sources':receipts,'artifact':artifact}
(out/'identity.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result,indent=2))
