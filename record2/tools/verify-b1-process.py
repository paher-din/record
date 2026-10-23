"""Run B1 gated process-death checks on the explicitly selected dedicated AVD."""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import queue
import subprocess
import threading
import time
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", required=True)
parser.add_argument("--serial", required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
output = root / "build/b1" / ("process-" + uuid.uuid4().hex)
output.mkdir(parents=True)
package = "com.paherdin.record2.debug"
runner = package + ".test/androidx.test.runner.AndroidJUnitRunner"
test_class = "com.paherdin.record2.storage.FinanceProcessContractTest"


def adb(*command, timeout=30):
    result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, text=True, encoding="utf-8", timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"adb failed ({result.returncode}): {command}: {result.stderr}")
    return result.stdout.strip()


assert adb("emu", "avd", "name").splitlines()[0] == "record2-schema-api34"
assert adb("shell", "getprop", "ro.build.version.sdk") == "34"
assert adb("shell", "getprop", "ro.kernel.qemu") == "1"
assert adb("shell", "getprop", "sys.boot_completed") == "1"
apks = {}
for suffix, path in [("", "app/build/outputs/apk/debug/app-debug.apk"), (".test", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")]:
    installed = adb("shell", "pm", "path", package + suffix).splitlines()
    assert len(installed) == 1 and installed[0].startswith("package:")
    digest = hashlib.sha256((root / path).read_bytes()).hexdigest()
    assert adb("shell", "sha256sum", installed[0][8:]).split()[0] == digest
    apks[package + suffix] = digest

results = []
for scenario in ["INIT", "DRAFT_BEFORE", "DRAFT_AFTER", "SAVE_WRITE", "SAVE_CONSUME", "SAVE_AFTER", "EDIT_WRITE"]:
    name = "b1-contract-" + scenario.lower().replace("_", "-") + "-" + uuid.uuid4().hex + ".db"
    common = ["shell", "am", "instrument", "-w", "-r", "-e", "databaseName", name, "-e", "scenario", scenario]
    process = subprocess.Popen([args.adb, "-s", args.serial, *common, "-e", "class", test_class + "#prepare", runner], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
    lines = queue.Queue()
    thread = threading.Thread(target=lambda: [lines.put(line) for line in process.stdout], daemon=True)
    thread.start()
    transcript = []
    baseline = None
    deadline = time.monotonic() + 60
    while baseline is None and time.monotonic() < deadline:
        try:
            line = lines.get(timeout=1)
        except queue.Empty:
            if process.poll() is not None:
                break
            continue
        transcript.append(line)
        if line.startswith("INSTRUMENTATION_STATUS: b1_ready="):
            baseline = line.split("b1_ready=", 1)[1].strip()
    if baseline is None:
        (output / f"{scenario}-prepare.txt").write_text("".join(transcript), encoding="utf-8")
        adb("shell", "am", "force-stop", package)
        raise RuntimeError(f"Missing process gate for {scenario}; see {output}")
    physical = json.loads(base64.b64decode(baseline))
    assert len(physical) == 14
    old_pid = adb("shell", "pidof", package)
    assert old_pid
    adb("shell", "am", "force-stop", package)
    process.wait(timeout=15)
    thread.join(timeout=5)
    while not lines.empty():
        transcript.append(lines.get())
    (output / f"{scenario}-prepare.txt").write_text("".join(transcript), encoding="utf-8")
    gone = subprocess.run([args.adb, "-s", args.serial, "shell", "pidof", package], capture_output=True, text=True, timeout=10)
    assert not gone.stdout.strip(), "Old target process survived force-stop"
    launch = adb("shell", "am", "start", "-W", "-n", package + "/com.paherdin.record2.MainActivity")
    assert "Status: ok" in launch
    new_pid = adb("shell", "pidof", package)
    assert new_pid and set(new_pid.split()).isdisjoint(old_pid.split())
    foreground = adb("shell", "dumpsys", "activity", "activities")
    assert any(package in line and ("mResumedActivity" in line or "topResumedActivity" in line) for line in foreground.splitlines())
    verified = adb(*common, "-e", "baseline", baseline, "-e", "class", test_class + "#verifyAfterHostRestart", runner, timeout=60)
    (output / f"{scenario}-verify.txt").write_text(verified, encoding="utf-8")
    assert "OK (1 test)" in verified and "INSTRUMENTATION_STATUS_CODE: -2" not in verified, f"Verification failed: {scenario}; see {output}"
    results.append({"scenario": scenario, "database": name, "old_pid": old_pid, "new_activity_pid": new_pid, "result": "PASS"})
    print(scenario + ": PASS", flush=True)

summary = {"result": "PASS", "serial": args.serial, "avd": "record2-schema-api34", "apks": apks, "cases": results}
(output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print("Evidence: " + str(output), flush=True)
