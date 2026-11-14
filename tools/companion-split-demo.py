"""Emulator-only live SDK split-window probe and optional parked showcase. No normal diary reset."""
import argparse
import json
import pathlib
import re
import subprocess
import threading
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "doc/design/companion-v6/android-evidence"
NAV = "ai.umos.maps.android.navigation.app/.MainActivity"
PROBE = "io.chagok.app.smoke/io.chagok.app.companion.CompanionDrivingProbeActivity"


def adb(*args):
    return subprocess.check_output(["adb", *map(str, args)], text=True, encoding="utf-8").strip()


def request(path, method="GET"):
    with urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8088" + path, method=method), timeout=8) as r:
        return json.load(r)


def panes():
    result = {}
    for line in adb("shell", "am", "stack", "list").splitlines():
        match = re.search(r"taskId=(\d+): (\S+) bounds=\[(\d+),(\d+)\]\[(\d+),(\d+)\].*visible=(true|false)", line)
        if match:
            task, component, left, top, right, bottom, visible = match.groups()
            result[component.split("/")[0]] = dict(task=int(task), left=int(left), top=int(top), right=int(right), bottom=int(bottom), visible=visible == "true")
    return result


def arrange(user, package="io.chagok.app.smoke"):
    # These are the observed Pleos 2.1.0 2560x1440 window bar bounds, not forced task bounds.
    if "2560x1440" not in adb("shell", "wm", "size"):
        raise RuntimeError("Arrange requires the verified Pleos 2560x1440 emulator layout")
    adb("shell", "am", "start", "--user", user, "-n", NAV)
    time.sleep(2)
    for _ in range(5):
        p = panes(); nav = p.get(NAV.split('/')[0]); app = p.get(package)
        print("Window bounds:", nav, app, flush=True)
        if nav and app and nav["visible"] and app["visible"] and nav["left"] == 853 and app["left"] >= 1700 and app["right"] - app["left"] < 900:
            return p
        if nav and nav["visible"] and nav["right"] - nav["left"] > 1000 or app and app["visible"] and app["right"] - app["left"] > 1000:
            adb("shell", "input", "swipe", 2546, 720, 1650, 720, 1000)
        elif nav and app and nav["visible"] and app["visible"]:
            adb("shell", "input", "tap", 1706, 720)
        else:
            adb("shell", "am", "start", "--user", user, "-n", NAV)
        time.sleep(3)
    raise RuntimeError("Pleos did not arrange navigation left and companion right")


def main():
    global EVIDENCE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--showcase", action="store_true", help="Leave a labelled parked preview after successful live driving test")
    parser.add_argument("--evidence-dir", type=pathlib.Path, help="Save this run separately from earlier verification evidence")
    args = parser.parse_args()
    if args.evidence_dir:
        EVIDENCE = args.evidence_dir.resolve()
    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("This probe only controls an emulator")
    user = adb("shell", "am", "get-current-user")
    adb("shell", "am", "force-stop", "--user", user, "io.chagok.app")
    adb("shell", "am", "force-stop", "--user", user, "io.chagok.app.smoke")
    adb("forward", "tcp:8088", "tcp:8088")
    stopped = threading.Event()
    def heartbeat():
        while not stopped.is_set():
            try: request("/browser/session?client=chagok-split-probe", "POST")
            except Exception: pass
            stopped.wait(.75)
    thread = threading.Thread(target=heartbeat, daemon=True); thread.start()
    original = None; record = None; process = None; watchdog = None; success = False
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    try:
        time.sleep(1); original = request("/vehicle/state")
        request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
        request("/vehicle/gear?gear=Park", "PUT")
        command = ["adb", "shell", "am", "instrument", "--user", user, "-w", "-e", "virtualConsole", "true", "-e", "splitProbe", "true", "-e", "class", "io.chagok.app.CompanionSplitWindowTest", "io.chagok.app.smoke.test/androidx.test.runner.AndroidJUnitRunner"]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
        watchdog = threading.Timer(150, process.kill); watchdog.start()
        lines = []
        with (EVIDENCE / "split-window-smoke.log").open("w", encoding="utf-8") as log:
            for line in process.stdout:
                log.write(line); log.flush(); lines.append(line)
                if "chagok_signal=" not in line: continue
                signal = line.strip().split("chagok_signal=", 1)[1]
                print("Probe:", signal, flush=True)
                if signal in ("RESET", "PARK"):
                    request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT"); request("/vehicle/gear?gear=Park", "PUT")
                    request("/vehicle/body?front_wiper=Off", "PUT"); request("/vehicle/turn-signal?mode=Off", "PUT")
                elif signal == "ARRANGE_SPLIT":
                    p = arrange(user); (EVIDENCE / "companion-split-bounds.json").write_text(json.dumps(p, indent=2), encoding="utf-8")
                elif signal == "DRIVE":
                    request("/vehicle/gear?gear=Drive", "PUT"); request("/vehicle/controls?speed=25&accelerator=0&brake=0", "PUT")
                elif signal == "FOCUS_NAVIGATION":
                    p = panes()[NAV.split('/')[0]]
                    adb("shell", "input", "tap", (p["left"] + p["right"]) // 2, 400)
                elif signal == "RECORD_SPLIT":
                    record = subprocess.Popen(["adb", "shell", "screenrecord", "--size", "1920x1080", "--bit-rate", "8M", "--time-limit", "14", "/sdcard/chagok-split-driving.mp4"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                elif signal == "ACCELERATE":
                    for speed in (35, 45, 55, 65):
                        request(f"/vehicle/controls?speed={speed}&accelerator=0&brake=0", "PUT"); time.sleep(.45)
        process.wait(); output = "".join(lines); print(output[-3500:])
        if process.returncode or "FAILURES!!!" in output or "OK (" not in output:
            raise RuntimeError("Split probe failed; see split-window-smoke.log")
        for name in ("companion-split-driving.png", "companion-split-app.png", "companion-split-driving.json"):
            with (EVIDENCE / name).open("wb") as out:
                subprocess.run(["adb", "exec-out", "run-as", "io.chagok.app.smoke", "--user", user, "cat", "files/" + name], stdout=out, check=True)
        success = True
    finally:
        if watchdog: watchdog.cancel()
        if process and process.poll() is None: process.kill(); process.wait(timeout=5)
        if record:
            record.wait(timeout=25)
            adb("pull", "/sdcard/chagok-split-driving.mp4", str(EVIDENCE / "companion-split-driving.mp4"))
        adb("shell", "am", "force-stop", "--user", user, "io.chagok.app.smoke")
        try:
            request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT"); request("/vehicle/gear?gear=Park", "PUT")
            if original:
                request("/vehicle/body?front_wiper=" + urllib.parse.quote(original["body"]["wipers"]["front"]["mode"]), "PUT")
                request("/vehicle/turn-signal?mode=" + urllib.parse.quote(original["lights"]["turn_signal_mode"]), "PUT")
        finally:
            stopped.set(); thread.join(timeout=3)
    if success and args.showcase:
        adb("shell", "am", "start", "--user", user, "-n", PROBE, "--ez", "showcase", "true")
        time.sleep(2); arrange(user)
        print("Left navigation / right parked showcase ready. Normal app data was not reset.")


if __name__ == "__main__":
    main()
