from pathlib import Path
import glob
import hashlib
import json
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path.cwd().resolve()
assert ROOT.name == "record2", ROOT
OUT = ROOT / "build/e0"
OUT.mkdir(parents=True, exist_ok=True)

SENTINEL = b"r2-e0-synthetic-9f4c7a2e6b1d4c8f"
SENTINEL_TEXT = SENTINEL.decode()
SCHEMA = ROOT / "app/schemas/com.paherdin.record2.storage.RecordDatabase/1.json"
SCHEMA_LF_SHA256 = "94b02a94b5a824bd0d8e65e3a7594a08f1a35e6fe27e043b520d292b11fa8d75"
NS = "{http://schemas.android.com/apk/res/android}"
DOMAINS = {
    "root", "file", "database", "sharedpref", "external",
    "device_root", "device_file", "device_database", "device_sharedpref",
}
EXPECTED = {
    "debug": ("app/build/outputs/apk/debug/app-debug.apk", "com.paherdin.record2.debug"),
    "release": ("app/build/outputs/apk/release/app-release-unsigned.apk", "com.paherdin.record2"),
    "androidTest": (
        "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
        "com.paherdin.record2.debug.test",
    ),
}
E0_CLASSES = (
    "com/paherdin/record2/ai/security/AiSecurityPolicy",
    "com/paherdin/record2/ai/security/KeystoreCredentialVault",
    "com/paherdin/record2/storage/RoomAiSecurityService",
)
CONTROL_FIELDS = [
    "id", "mode", "selectedReceiverIdentity", "grantReceiverIdentity",
    "grantScopeVersion", "grantDisclosureVersion", "grantedAt", "authEpoch",
]

sdk_line = [
    line.split("=", 1)[1]
    for line in (ROOT / "local.properties").read_text().splitlines()
    if line.startswith("sdk.dir=")
]
assert len(sdk_line) == 1
sdk = Path(sdk_line[0].replace("\\\\", "\\").replace("\\:", ":"))
aapt = sdk / "build-tools/36.0.0/aapt2.exe"
adb = sdk / "platform-tools/adb.exe"
assert aapt.is_file() and adb.is_file()


def tree(text):
    stack = []
    top = None
    for line in text.splitlines():
        element = re.match(r"^(\s*)E: ([^\s(]+)", line)
        if element:
            indent = len(element[1])
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = {"name": element[2], "attrs": {}, "children": []}
            if stack:
                stack[-1][1]["children"].append(node)
            else:
                top = node
            stack.append((indent, node))
        else:
            attr = re.match(r"^\s*A: ([^\s=(]+)(?:\(0x[0-9a-f]+\))?=(.*)", line)
            if attr:
                quoted = re.search(r'"([^"]*)"', attr[2])
                value = quoted[1] if quoted else attr[2].strip()
                stack[-1][1]["attrs"][attr[1].split(":")[-1]] = value
    assert top is not None
    return top


def walk(node):
    yield node
    for child in node["children"]:
        yield from walk(child)


def number(value):
    if value in {"true", "false"}:
        return int(value == "true")
    hex_value = re.search(r"0x[0-9a-f]+$", value)
    return int(hex_value[0], 16) if hex_value else int(value)


def dump(apk, file):
    return tree(
        subprocess.check_output(
            [str(aapt), "dump", "xmltree", str(apk), "--file", file], encoding="utf-8"
        )
    )


def resource_file(table, name, resource_id):
    found = re.search(
        r"(?m)^\s*resource (0x[0-9a-f]+) [^\n]*\bxml/" + name + r"\b[^\n]*\n", table
    )
    assert found is not None, name
    assert int(found[1], 16) == number(resource_id), name
    block = re.split(r"(?m)^\s*resource ", table[found.end():], maxsplit=1)[0]
    file = re.search(r"\(file\) (\S+)", block)
    assert file is not None and file[1].startswith("res/")
    return file[1]


# A1/G3 baseline manifests still describe the same app identity and backup policy.
plain_count = 0
for manifest in [ROOT / "app/src/main/AndroidManifest.xml"] + [
    path
    for layer in ["merged_manifests", "packaged_manifests"]
    for variant in ["debug", "release"]
    for path in (ROOT / "app/build/intermediates" / layer / variant).glob("*/AndroidManifest.xml")
]:
    doc = ET.parse(manifest).getroot()
    app = doc.find("application")
    assert app.get(NS + "allowBackup") == "false", manifest
    assert app.get(NS + "usesCleartextTraffic") == "false", manifest
    assert "android.permission.INTERNET" in {
        permission.get(NS + "name") for permission in doc.findall("uses-permission")
    }, manifest
    plain_count += 1
assert plain_count == 5, plain_count

apk_results = {}
for variant, (relative, package) in EXPECTED.items():
    apk = ROOT / relative
    assert apk.is_file(), apk
    doc = dump(apk, "AndroidManifest.xml")
    assert doc["attrs"]["package"] == package, variant
    nodes = list(walk(doc))
    for node in nodes:
        assert not any(
            value.startswith("com.paherdin.record.") or ".poc.c1" in value
            for value in node["attrs"].values()
        ), variant
    if variant == "androidTest":
        instrumentation = next(node for node in nodes if node["name"] == "instrumentation")
        assert instrumentation["attrs"]["targetPackage"] == "com.paherdin.record2.debug"
    else:
        app = next(node for node in nodes if node["name"] == "application")
        assert number(app["attrs"]["allowBackup"]) == 0
        assert number(app["attrs"]["usesCleartextTraffic"]) == 0
        permissions = {
            node["attrs"]["name"] for node in nodes if node["name"].startswith("uses-permission")
        }
        assert "android.permission.INTERNET" in permissions
        assert permissions <= {
            "android.permission.INTERNET",
            package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        }, permissions
        sdk_node = next(node for node in nodes if node["name"] == "uses-sdk")
        assert number(sdk_node["attrs"]["minSdkVersion"]) == 34
        assert number(sdk_node["attrs"]["targetSdkVersion"]) == 34
        for node in nodes:
            if node["name"] in {"activity", "activity-alias", "service", "receiver", "provider"} and number(
                node["attrs"].get("exported", "0x0")
            ):
                name = node["attrs"]["name"]
                assert name in {
                    "com.paherdin.record2.MainActivity",
                    "androidx.profileinstaller.ProfileInstallReceiver",
                }, name

        table = subprocess.check_output(
            [str(aapt), "dump", "resources", str(apk)], encoding="utf-8"
        )
        for file, attr in [
            ("backup_rules", "fullBackupContent"),
            ("data_extraction_rules", "dataExtractionRules"),
        ]:
            rules = dump(apk, resource_file(table, file, app["attrs"][attr]))
            sections = [rules] if file == "backup_rules" else rules["children"]
            if file == "data_extraction_rules":
                assert {section["name"] for section in sections} == {"cloud-backup", "device-transfer"}
            for section in sections:
                excluded = section["children"]
                assert len(excluded) == 9, (variant, file)
                assert {node["attrs"].get("domain") for node in excluded} == DOMAINS
                assert all(
                    node["name"] == "exclude" and node["attrs"].get("path") == "."
                    for node in excluded
                )

    with zipfile.ZipFile(apk) as archive:
        dex = b"".join(
            archive.read(name)
            for name in archive.namelist()
            if re.fullmatch(r"classes\d*\.dex", name)
        )
        assert b"com/paherdin/record/" not in dex
        assert b"com/paherdin/record2/poc/" not in dex
        if variant == "androidTest":
            assert SENTINEL in dex
        else:
            assert SENTINEL not in dex
            for token in E0_CLASSES:
                assert token.encode() in dex, (variant, token)

    apk_results[variant] = {
        "package": package,
        "path": relative,
        "bytes": apk.stat().st_size,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }

# The accepted Schema v1 content must remain byte-identical after LF normalization.
raw_schema = SCHEMA.read_bytes()
schema_sha = hashlib.sha256(raw_schema.replace(b"\r\n", b"\n")).hexdigest()
assert schema_sha == SCHEMA_LF_SHA256, schema_sha
database = json.loads(raw_schema)["database"]
entities = {entity["tableName"]: entity for entity in database["entities"]}
control = entities["ai_control"]
assert [field["fieldPath"] for field in control["fields"]] == CONTROL_FIELDS
assert b"credential" not in raw_schema.lower()

# Production sources must not contain the synthetic marker, logging of credentials, hard-coded
# key material, or a manual ZIP writer (ZIP belongs to G0/G1 and is not implemented here).
source_findings = []
zip_references = []
for path in (ROOT / "app/src/main").rglob("*"):
    if not path.is_file() or path.suffix not in {".kt", ".xml"}:
        continue
    text = path.read_text(encoding="utf-8", errors="ignore")
    if SENTINEL_TEXT in text:
        source_findings.append(f"{path}: synthetic sentinel")
    if re.search(r"\bsk-[A-Za-z0-9]{16,}\b", text):
        source_findings.append(f"{path}: key-shaped literal")
    if "BEGIN PRIVATE KEY" in text:
        source_findings.append(f"{path}: private key block")
    if "android.util.Log" in text and (
        "ai/security" in path.as_posix() or "RoomAiSecurityService" in path.as_posix()
    ):
        source_findings.append(f"{path}: credential-path logging")
    if any(token in text for token in ("ZipOutputStream", "ZipFile", "ZipEntry")):
        zip_references.append(path.as_posix())
assert not source_findings, source_findings
assert not zip_references, zip_references


def instrument(path, count):
    text = path.read_text(encoding="utf-8")
    assert f"OK ({count} tests)" in text, path
    assert "FAILURES!!!" not in text, path
    assert text.count("INSTRUMENTATION_STATUS_CODE: 0") == count, path
    return {"tests": count, "failures": 0, "path": path.relative_to(ROOT).as_posix()}


e0_instrumentation = instrument(OUT / "instrumentation-final4.txt", 17)
regression = instrument(OUT / "instrumentation-regression4.txt", 24)
e0_text = (OUT / "instrumentation-final4.txt").read_text(encoding="utf-8")
assert "AiSecurityContractTest" in e0_text
assert "AiSecurityInitializationContractTest" in e0_text
assert "KeystoreCredentialVaultTest" in e0_text

for log in [
    OUT / "logcat-final4.txt",
    OUT / "logcat-regression4.txt",
]:
    text = log.read_text(encoding="utf-8", errors="ignore")
    assert SENTINEL_TEXT not in text, log
    assert "r2-e0-synthetic" not in text, log

host = {}
for variant in ["debug", "release"]:
    total = 0
    failures = 0
    skipped = 0
    e0 = 0
    for path in glob.glob(str(ROOT / f"app/build/test-results/test{variant.capitalize()}UnitTest/*.xml")):
        suite = ET.parse(path).getroot()
        total += int(suite.get("tests", "0"))
        failures += int(suite.get("failures", "0")) + int(suite.get("errors", "0"))
        skipped += int(suite.get("skipped", "0"))
        e0 += sum(
            1
            for case in suite.iter("testcase")
            if case.get("classname", "").startswith("com.paherdin.record2.ai.security")
        )
    assert failures == 0, variant
    assert skipped == 0, (variant, skipped)
    assert e0 == 8, (variant, e0)
    host[variant] = {"tests": total, "e0_tests": e0, "failures": failures, "skipped": skipped}

serial = "emulator-5580"
device = {}
if "--skip-device" not in sys.argv:
    assert serial in subprocess.check_output(
        [str(adb), "devices"], encoding="utf-8"
    ), "E0 device is not attached"
    device = {
        "serial": serial,
        "avd": subprocess.check_output(
            [str(adb), "-s", serial, "emu", "avd", "name"], encoding="utf-8"
        ).splitlines()[0].strip(),
        "api": subprocess.check_output(
            [str(adb), "-s", serial, "shell", "getprop", "ro.build.version.sdk"], encoding="utf-8"
        ).strip(),
        "fingerprint": subprocess.check_output(
            [str(adb), "-s", serial, "shell", "getprop", "ro.build.fingerprint"], encoding="utf-8"
        ).strip(),
    }
    assert device["avd"] == "record2-schema-api34", device
    assert device["api"] == "34", device

report = {
    "task": "E0 provider authorization and BYOK/Keystore",
    "result": "PASS",
    "scope": (
        "Non-visual authorization and credential-access seam with a synthetic credential; "
        "no real Provider, real Key, user data, UI or manual ZIP."
    ),
    "schema_v1_lf_sha256": schema_sha,
    "plain_manifests": plain_count,
    "apks": apk_results,
    "backup_layers": {
        "manifest_allowBackup": False,
        "legacy_full_backup_rules": "9 domains excluded",
        "api31_cloud_and_device_transfer_rules": "9 domains excluded in both sections",
        "credential_location": "Context.noBackupFilesDir (outside backup and ZIP scope)",
    },
    "secret_scan": {
        "synthetic_sentinel": SENTINEL_TEXT,
        "production_apks": "absent",
        "android_test_apk": "present (test-only)",
        "logcat": "absent",
        "app_storage_and_room": "asserted by AiSecurityContractTest",
        "manual_zip": "NOT IMPLEMENTED; G0/G1 owns actual ZIP exclusion",
    },
    "tests": {
        "host": host,
        "e0_device": e0_instrumentation,
        "schema_and_b1_regression": regression,
    },
    "device": device,
    "limits": [
        "No real DeepSeek request, real API key, UI, session history or manual ZIP was exercised.",
        "D0/D1 must revalidate at the actual request-start boundary and E2 must order revocation/cancellation; E0 proves the permit/credential seam and state matrix, not a race-free production network gate.",
        "E2/G0 must bind a permit to the request's dataGeneration or reject it after restore; E0's permit binds receiver, authEpoch and credential status only.",
        "Keystore key invalidation is simulated by deleting the test key; no user-authentication-bound key was used.",
    ],
}
(OUT / "verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(
    "PASS: E0 authorization/credential state, Keystore, schema, APK/backup rules, "
    "secret sentinels and 41 device tests verified"
)
