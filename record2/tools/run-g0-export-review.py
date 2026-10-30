"""Run one isolated G0 snapshot architecture experiment on the existing G1 API34 AVD.

Prototype only: no production source edits, user DBs, keys or schema migration.
Example: python record2/tools/run-g0-export-review.py --adb ABS_ADB --install
         --route room-current --rows 50000 --method snapshotAndResourceExperiment
Each invocation gets an immutable evidence directory; FAIL is returned as a nonzero exit.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", required=True)
parser.add_argument("--serial", default="emulator-5594")
parser.add_argument("--route", default="room-current")
parser.add_argument("--rows", type=int, default=50000)
parser.add_argument("--large", action="store_true")
parser.add_argument("--bulk-write", action="store_true")
parser.add_argument("--fresh-export", action="store_true", help="Seed/close in one PID, export in a fresh PID")
parser.add_argument("--no-concurrent", action="store_true")
parser.add_argument("--install", action="store_true")
parser.add_argument("--method", default="snapshotAndResourceExperiment")
parser.add_argument("--timeout", type=int, default=600)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
run_id = uuid.uuid4().hex
output = root / "build/g0-export-review" / run_id
output.mkdir(parents=True)
package = "com.paherdin.record2.debug"
runner = package + ".test/androidx.test.runner.AndroidJUnitRunner"


def adb(*command, timeout=60):
    result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"adb failed: {command}: {result.stderr}")
    return result.stdout.strip()


assert adb("emu", "avd", "name").splitlines()[0] == "record-api34"
assert adb("shell", "getprop", "ro.build.version.sdk") == "34"
assert adb("shell", "getprop", "sys.boot_completed") == "1"
identity = {"runId": run_id, "route": args.route, "rows": args.rows, "large": args.large,
            "bulkWrite": args.bulk_write,
            "method": args.method, "serial": args.serial,
            "fingerprint": adb("shell", "getprop", "ro.build.fingerprint")}
for suffix, relative in [("", "app/build/outputs/apk/debug/app-debug.apk"),
                         (".test", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")]:
    path = root / relative
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if args.install:
        assert "Success" in adb("install", "-r", str(path), timeout=120)
    installed = adb("shell", "pm", "path", package + suffix).splitlines()
    assert len(installed) == 1 and installed[0].startswith("package:")
    assert adb("shell", "sha256sum", installed[0][8:]).split()[0] == digest
    identity["apk" + suffix] = digest
(output / "identity.json").write_text(json.dumps(identity, indent=2), encoding="utf-8")
selector = "com.paherdin.record2.backup.prototype.G0SnapshotArchitecturePrototype#" + args.method
command = [args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
           "-e", "class", selector, "-e", "route", args.route,
           "-e", "rows", str(args.rows), "-e", "large", str(args.large).lower(),
           "-e", "bulkWrite", str(args.bulk_write).lower(),
           "-e", "concurrent", str(not args.no_concurrent).lower(), "-e", "runId", run_id, runner]
prepare_pid = None
if args.fresh_export:
    prepare_id = run_id + "-prepare"
    prepare = command.copy()
    prepare[prepare.index(selector)] = "com.paherdin.record2.backup.prototype.G0SnapshotArchitecturePrototype#prepareFixtureForFreshProcess"
    prepare[prepare.index(run_id)] = prepare_id
    print(f"PREPARE {run_id} {args.route} {args.rows} large={args.large}", flush=True)
    prepared = subprocess.run(prepare, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=args.timeout)
    (output / "prepare.txt").write_text(prepared.stdout + prepared.stderr, encoding="utf-8")
    assert "OK (1 test)" in prepared.stdout, prepared.stdout[-4000:]
    prepare_logs = adb("logcat", "-d", "-v", "raw", "-s", "System.out:I")
    metadata = None
    for line in prepare_logs.splitlines():
        match = re.search(r"G0-PROTOTYPE (\{.*\})", line)
        if match:
            try:
                value = json.loads(match.group(1))
            except json.JSONDecodeError:
                continue  # Android may split long log lines; the private JSON is authoritative.
            if value.get("runId") == prepare_id:
                metadata = value
    assert metadata and "fixtureToken" in metadata
    prepare_pid = metadata["preparePid"]
    command[-1:-1] = ["-e", "fixtureToken", metadata["fixtureToken"]]
    (output / "prepare-metadata.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
(output / "command.json").write_text(json.dumps(command, indent=2), encoding="utf-8")
print(f"START {run_id} {args.route} {args.rows} large={args.large} {args.method}", flush=True)
started = time.monotonic()
try:
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=args.timeout)
    instrumentation = result.stdout + result.stderr
except subprocess.TimeoutExpired as error:
    adb("shell", "am", "force-stop", package)
    instrumentation = "HOST TIMEOUT: no success\n" + str(error.stdout)
(output / "instrumentation.txt").write_text(instrumentation, encoding="utf-8")
logs = adb("logcat", "-d", "-v", "raw", "-s", "System.out:I", "G0-PROTOTYPE:I")
reports = []
for line in logs.splitlines():
    match = re.search(r"G0-PROTOTYPE (\{.*\})", line)
    if match:
        try:
            candidate = json.loads(match.group(1))
        except json.JSONDecodeError:
            continue
        if candidate.get("runId") == run_id:
            reports.append(candidate)
try:
    full_report = json.loads(adb("shell", "run-as", package, "cat", f"no_backup/g0-prototype-reports/{run_id}.json"))
    assert full_report.get("runId") == run_id
    reports = [full_report]
except RuntimeError:
    pass  # A crashed/older prototype can have only log output; it is never inferred to pass.
(output / "metrics.json").write_text(json.dumps(reports, indent=2), encoding="utf-8")
passed = "OK (1 test)" in instrumentation and "INSTRUMENTATION_STATUS_CODE: 0" in instrumentation
if args.method == "snapshotAndResourceExperiment":
    passed = passed and len(reports) == 1 and reports[0].get("verdict") == "PASS"
if prepare_pid is not None:
    passed = passed and len(reports) == 1 and reports[0].get("exportPid") != prepare_pid
summary = {"runId": run_id, "passed": passed, "wallSeconds": round(time.monotonic()-started, 3),
           "evidence": str(output), "metrics": reports}
(output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False), flush=True)
if not passed:
    failures = [line for line in instrumentation.splitlines()
                if "stack=" in line or "Exception" in line or "Error" in line or "TIMEOUT" in line]
    print("\n".join(failures[:8]), flush=True)
sys.exit(0 if passed else 1)
