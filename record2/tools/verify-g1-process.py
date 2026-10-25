"""Check the G1 confirmed-restore force-stop matrix on the dedicated Record 2 AVD.

Each scenario uses the real target app process:

  1. #prepare seeds a distinct target, exports a source ZIP, then blocks inside preflight copy
     (PREFLIGHT) or at a restore checkpoint and reports g1_ready.
  2. The host captures the PID, force-stops the process (PID must vanish), relaunches
     MainActivity and waits for a fresh foreground process.
  3. #verify in the fresh process proves the checkpoint boundary and receipt authority:
       PREFLIGHT / AFTER_DELETE / BEFORE_RECEIPT / BEFORE_COMMIT
         -> 14-table baseline digest unchanged, no receipt, then a normal restore succeeds
       AFTER_COMMIT
         -> complete restored data, valid receipt, zero active slots, and receipt lookup from
            the pre-death generation returns Committed without re-importing.

Deterministic synthetic data only; no real key, network, user database or schema change.
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
parser.add_argument("--expect-avd", default="record-api34")
parser.add_argument("--checkpoints", default="PREFLIGHT,AFTER_DELETE,BEFORE_RECEIPT,BEFORE_COMMIT,AFTER_COMMIT")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
output = root / "build/g1" / ("process-" + uuid.uuid4().hex)
output.mkdir(parents=True)
package = "com.paherdin.record2.debug"
runner = package + ".test/androidx.test.runner.AndroidJUnitRunner"
test_class = "com.paherdin.record2.backup.G1ProcessRecoveryContractTest"


def adb(*command, timeout=120):
    result = subprocess.run(
        [args.adb, "-s", args.serial, *command],
        capture_output=True, text=True, encoding="utf-8", timeout=timeout,
    )
    if result.returncode:
        raise RuntimeError(f"adb failed ({result.returncode}): {command}: {result.stderr}")
    return result.stdout.strip()


assert adb("emu", "avd", "name").splitlines()[0] == args.expect_avd
for key, value in [("ro.build.version.sdk", "34"), ("ro.kernel.qemu", "1"), ("sys.boot_completed", "1")]:
    assert adb("shell", "getprop", key) == value
apks = {}
for suffix, path in [
    ("", "app/build/outputs/apk/debug/app-debug.apk"),
    (".test", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"),
]:
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


def read_until_ready(process, lines, timeout):
    transcript = []
    baseline = None
    deadline = time.monotonic() + timeout
    while baseline is None and time.monotonic() < deadline:
        try:
            line = lines.get(timeout=1)
        except queue.Empty:
            if process.poll() is not None:
                break
            continue
        transcript.append(line)
        if line.startswith("INSTRUMENTATION_STATUS: g1_ready="):
            baseline = line.split("g1_ready=", 1)[1].strip()
    return baseline, transcript


def run_scenario(checkpoint):
    scenario = output / checkpoint.lower()
    scenario.mkdir(parents=True)
    name = "g1-process-" + checkpoint.lower() + "-" + uuid.uuid4().hex + ".db"
    common = ["shell", "am", "instrument", "-w", "-r", "-e", "databaseName", name, "-e", "checkpoint", checkpoint]
    process = subprocess.Popen(
        [args.adb, "-s", args.serial, *common, "-e", "class", test_class + "#prepare", runner],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8",
    )
    lines = queue.Queue()
    threading.Thread(target=lambda: [lines.put(line) for line in process.stdout], daemon=True).start()
    try:
        baseline, prepare = read_until_ready(process, lines, 240)
        if baseline is None:
            (scenario / "prepare.txt").write_text("".join(prepare), encoding="utf-8")
            raise RuntimeError(f"{checkpoint}: missing g1_ready gate; see {scenario}")
        ready = json.loads(baseline)
        assert ready["checkpoint"] == checkpoint and ready["database"] == name
        assert ready["zip"] and ready["zipSha"]
        old_pid = adb("shell", "pidof", package)
        assert old_pid, f"{checkpoint}: prepare process must be alive at the gate"
        verify_installed_identity()
        # Probe the extra receipt-reuse input parsed by #verify.
        if checkpoint == "AFTER_COMMIT":
            assert ready.get("preflightGeneration")
        adb("shell", "am", "force-stop", package)
        process.wait(timeout=20)
        gone = subprocess.run(
            [args.adb, "-s", args.serial, "shell", "pidof", package],
            capture_output=True, text=True, timeout=10,
        )
        assert not gone.stdout.strip(), f"{checkpoint}: target process survived force-stop"
        launch = adb("shell", "am", "start", "-W", "-n", package + "/com.paherdin.record2.MainActivity")
        (scenario / "launch.txt").write_text(launch, encoding="utf-8")
        assert "Error:" not in launch, launch
        verify_installed_identity()
        start_deadline = time.monotonic() + 30
        while True:
            pid = subprocess.run(
                [args.adb, "-s", args.serial, "shell", "pidof", package],
                capture_output=True, text=True, timeout=10,
            ).stdout.strip()
            activities = adb("shell", "dumpsys", "activity", "activities")
            resumed = [
                line for line in activities.splitlines()
                if package in line and ("mResumedActivity" in line or "topResumedActivity" in line)
            ]
            if pid and set(pid.split()).isdisjoint(old_pid.split()) and resumed:
                break
            assert time.monotonic() < start_deadline, f"{checkpoint}: target did not reach a fresh foreground process"
            verify_installed_identity()
            time.sleep(0.5)
        verified = adb(
            *common, "-e", "class", test_class + "#verify", runner, timeout=240,
        )
        (scenario / "verify.txt").write_text(verified, encoding="utf-8")
        assert "OK (1 test)" in verified and "INSTRUMENTATION_STATUS_CODE: -2" not in verified, (
            f"{checkpoint}: verification failed; see {scenario}"
        )
        verify_installed_identity()
        (scenario / "summary.json").write_text(json.dumps({
            "result": "PASS",
            "checkpoint": checkpoint,
            "database": name,
            "old_pid": old_pid,
            "new_pid": pid,
            "ready": ready,
        }, indent=2), encoding="utf-8")
        print(checkpoint + ": PASS", flush=True)
    except Exception:
        subprocess.run([args.adb, "-s", args.serial, "shell", "am", "force-stop", package], capture_output=True)
        process.terminate()
        raise


checkpoints = [item.strip() for item in args.checkpoints.split(",") if item.strip()]
for checkpoint in checkpoints:
    run_scenario(checkpoint)
(output / "summary.json").write_text(json.dumps({
    "result": "PASS",
    "serial": args.serial,
    "avd": args.expect_avd,
    "apks": apks,
    "checkpoints": checkpoints,
}, indent=2), encoding="utf-8")
print("G1-PROCESS: PASS", flush=True)
print("Evidence: " + str(output), flush=True)
