"""Check the E2 run-lifecycle process death on the explicitly selected dedicated Record 2 AVD.

One scenario (RUN_LIFECYCLE): #prepare leaves an owned run in flight over a scripted transport
that never answers, the host captures the PID, force-stops the process (PID must vanish),
relaunches MainActivity and waits for a fresh foreground PID, then #verify in the fresh process
proves: leftover run INTERRUPTED, request log unchanged across cold start (zero automatic
model/tool calls), and one explicitly launched new run that never replays the interrupted input.
"""

import argparse
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
output = root / "build/e2" / ("process-" + uuid.uuid4().hex)
output.mkdir(parents=True)
package = "com.paherdin.record2.debug"
runner = package + ".test/androidx.test.runner.AndroidJUnitRunner"
test_class = "com.paherdin.record2.storage.E2ProcessRecoveryContractTest"


def adb(*command, timeout=60):
    result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, text=True, encoding="utf-8", timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"adb failed ({result.returncode}): {command}: {result.stderr}")
    return result.stdout.strip()


assert adb("emu", "avd", "name").splitlines()[0] == "record2-schema-api34"
for key, value in [("ro.build.version.sdk", "34"), ("ro.kernel.qemu", "1"), ("sys.boot_completed", "1")]:
    assert adb("shell", "getprop", key) == value
apks = {}
for suffix, path in [("", "app/build/outputs/apk/debug/app-debug.apk"), (".test", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")]:
    installed = adb("shell", "pm", "path", package + suffix).splitlines()
    assert len(installed) == 1 and installed[0].startswith("package:")
    digest = hashlib.sha256((root / path).read_bytes()).hexdigest()
    assert adb("shell", "sha256sum", installed[0][8:]).split()[0] == digest
    apks[package + suffix] = digest


def verify_installed_identity():
    for target, expected in apks.items():
        installed = adb("shell", "pm", "path", target).splitlines()
        assert len(installed) == 1 and installed[0].startswith("package:")
        actual = adb("shell", "sha256sum", installed[0][8:]).split()[0]
        if actual != expected:
            raise RuntimeError(f"Another task replaced {target}; stop and coordinate the shared AVD")


verify_installed_identity()
name = "e2-process-run-lifecycle-" + uuid.uuid4().hex + ".db"
common = ["shell", "am", "instrument", "-w", "-r", "-e", "databaseName", name]
process = subprocess.Popen([args.adb, "-s", args.serial, *common, "-e", "class", test_class + "#prepare", runner], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
lines = queue.Queue()
thread = threading.Thread(target=lambda: [lines.put(line) for line in process.stdout], daemon=True)
thread.start()
transcript = []
baseline = None
deadline = time.monotonic() + 90
while baseline is None and time.monotonic() < deadline:
    try:
        line = lines.get(timeout=1)
    except queue.Empty:
        if process.poll() is not None:
            break
        continue
    transcript.append(line)
    if line.startswith("INSTRUMENTATION_STATUS: e2_ready="):
        baseline = line.split("e2_ready=", 1)[1].strip()
if baseline is None:
    (output / "prepare.txt").write_text("".join(transcript), encoding="utf-8")
    verify_installed_identity()
    adb("shell", "am", "force-stop", package)
    raise RuntimeError(f"Missing e2_ready gate; see {output}")
ready = json.loads(baseline)
assert ready["requests"] == 1 and ready["sessionId"]
old_pid = adb("shell", "pidof", package)
assert old_pid, "prepare process must be alive when the gate fires"
verify_installed_identity()
adb("shell", "am", "force-stop", package)
process.wait(timeout=15)
thread.join(timeout=5)
while not lines.empty():
    transcript.append(lines.get())
(output / "prepare.txt").write_text("".join(transcript), encoding="utf-8")
gone = subprocess.run([args.adb, "-s", args.serial, "shell", "pidof", package], capture_output=True, text=True, timeout=10)
assert not gone.stdout.strip(), "Target process survived force-stop"
launch = adb("shell", "am", "start", "-W", "-n", package + "/com.paherdin.record2.MainActivity")
(output / "launch.txt").write_text(launch, encoding="utf-8")
assert "Error:" not in launch, launch
verify_installed_identity()
start_deadline = time.monotonic() + 30
while True:
    pid = subprocess.run([args.adb, "-s", args.serial, "shell", "pidof", package], capture_output=True, text=True, timeout=10)
    new_pid = pid.stdout.strip()
    foreground = adb("shell", "dumpsys", "activity", "activities")
    resumed = [line for line in foreground.splitlines() if package in line and ("mResumedActivity" in line or "topResumedActivity" in line)]
    if new_pid and set(new_pid.split()).isdisjoint(old_pid.split()) and resumed:
        break
    assert time.monotonic() < start_deadline, "Target did not reach a new foreground process"
    verify_installed_identity()
    time.sleep(0.5)
(output / "process.json").write_text(json.dumps({"old_pid": old_pid, "new_pid": new_pid, "resumed": resumed}, indent=2), encoding="utf-8")
verified = adb(
    *common, "-e", "sessionId", ready["sessionId"], "-e", "requestCount", str(ready["requests"]),
    "-e", "class", test_class + "#verify", runner,
)
(output / "verify.txt").write_text(verified, encoding="utf-8")
assert "OK (1 test)" in verified and "INSTRUMENTATION_STATUS_CODE: -2" not in verified, f"Verification failed; see {output}"
verify_installed_identity()
summary = {
    "result": "PASS",
    "scenario": "RUN_LIFECYCLE",
    "serial": args.serial,
    "avd": "record2-schema-api34",
    "apks": apks,
    "database": name,
    "old_pid": old_pid,
    "new_pid": new_pid,
    "baseline_requests": ready["requests"],
}
(output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print("RUN_LIFECYCLE: PASS", flush=True)
print("Evidence: " + str(output), flush=True)
