"""Check B2 process death on the explicitly selected dedicated Record 2 AVD."""

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
parser.add_argument("--scenario", choices=[operation + "_" + boundary for operation in ["CREATE", "EDIT", "DELETE", "RESTORE"] for boundary in ["BEFORE", "AFTER"]])
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
output = root / "build/b2" / ("process-" + uuid.uuid4().hex)
output.mkdir(parents=True)
package = "com.paherdin.record2.debug"
runner = package + ".test/androidx.test.runner.AndroidJUnitRunner"
test_class = "com.paherdin.record2.storage.NoteProcessContractTest"


def adb(*command, timeout=30):
    result = subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, text=True, encoding="utf-8", timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"adb failed ({result.returncode}): {command}: {result.stderr}")
    return result.stdout.strip()


assert adb("emu", "avd", "name").splitlines()[0] == "record2-b2-api34"
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

results = []
for operation in ["CREATE", "EDIT", "DELETE", "RESTORE"]:
    for boundary in ["BEFORE", "AFTER"]:
        scenario = operation + "_" + boundary
        if args.scenario and args.scenario != scenario:
            continue
        verify_installed_identity()
        name = "b2-contract-" + scenario.lower().replace("_", "-") + "-" + uuid.uuid4().hex + ".db"
        note_id = str(uuid.uuid4())
        common = ["shell", "am", "instrument", "-w", "-r", "-e", "databaseName", name, "-e", "scenario", scenario, "-e", "noteId", note_id]
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
            if line.startswith("INSTRUMENTATION_STATUS: b2_ready="):
                baseline = line.split("b2_ready=", 1)[1].strip()
        if baseline is None:
            (output / f"{scenario}-prepare.txt").write_text("".join(transcript), encoding="utf-8")
            verify_installed_identity()
            adb("shell", "am", "force-stop", package)
            raise RuntimeError(f"Missing process gate for {scenario}; see {output}")
        assert len(json.loads(base64.b64decode(baseline))) == 14
        old_pid = adb("shell", "pidof", package)
        assert old_pid
        new_pids = []
        for restart in range(2):
            verify_installed_identity()
            adb("shell", "am", "force-stop", package)
            if restart == 0:
                process.wait(timeout=15)
                thread.join(timeout=5)
                while not lines.empty():
                    transcript.append(lines.get())
                (output / f"{scenario}-prepare.txt").write_text("".join(transcript), encoding="utf-8")
            gone = subprocess.run([args.adb, "-s", args.serial, "shell", "pidof", package], capture_output=True, text=True, timeout=10)
            assert not gone.stdout.strip(), "Target process survived force-stop"
            launch = adb("shell", "am", "start", "-W", "-n", package + "/com.paherdin.record2.MainActivity")
            (output / f"{scenario}-launch-{restart + 1}.txt").write_text(launch, encoding="utf-8")
            assert "Error:" not in launch, launch
            verify_installed_identity()
            # am -W has its own short wait budget; prove the actual bounded startup state.
            start_deadline = time.monotonic() + 30
            while True:
                pid = subprocess.run([args.adb, "-s", args.serial, "shell", "pidof", package], capture_output=True, text=True, timeout=10)
                new_pid = pid.stdout.strip()
                foreground = adb("shell", "dumpsys", "activity", "activities")
                resumed = [line for line in foreground.splitlines() if package in line and ("mResumedActivity" in line or "topResumedActivity" in line)]
                if new_pid and set(new_pid.split()).isdisjoint(old_pid.split()) and resumed:
                    break
                assert time.monotonic() < start_deadline, f"Target did not reach a new foreground process: {scenario}"
                verify_installed_identity()
                time.sleep(0.5)
            new_pids.append(new_pid)
            (output / f"{scenario}-process-{restart + 1}.json").write_text(json.dumps({"old_pid": old_pid, "new_pid": new_pid, "resumed": resumed}, indent=2), encoding="utf-8")
            verified = adb(*common, "-e", "baseline", baseline, "-e", "class", test_class + "#verifyAfterHostRestart", runner, timeout=60)
            (output / f"{scenario}-verify-{restart + 1}.txt").write_text(verified, encoding="utf-8")
            assert "OK (1 test)" in verified and "INSTRUMENTATION_STATUS_CODE: -2" not in verified, f"Verification failed: {scenario}; see {output}"
            verify_installed_identity()
        results.append({"scenario": scenario, "database": name, "note_id": note_id, "old_pid": old_pid, "new_activity_pids": new_pids, "result": "PASS"})
        (output / "progress.json").write_text(json.dumps({"serial": args.serial, "avd": "record2-b2-api34", "apks": apks, "completed_cases": results}, indent=2), encoding="utf-8")
        print(scenario + ": PASS (two restarts)", flush=True)

summary = {"result": "PASS", "serial": args.serial, "avd": "record2-b2-api34", "apks": apks, "cases": results}
(output / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print("Evidence: " + str(output), flush=True)
