"""Read-only app SDK audit; signal changes are confined to the Pleos emulator console."""
import json
import argparse
import pathlib
import subprocess
import threading
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "doc" / "design" / "companion-v5"
BASE = "http://127.0.0.1:8088"


def request(path, method="GET"):
    with urllib.request.urlopen(urllib.request.Request(BASE + path, method=method), timeout=8) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wiper", action="store_true", help="Read OFF/LOW/HIGH/AUTO settings without the driving probe")
    args = parser.parse_args()
    report_name = "wiper-sdk-probe" if args.wiper else "sdk-probe"
    device_report = "wiper-sdk-probe.json" if args.wiper else "companion-sdk-probe.json"
    test_name = "auditWiperSwitchLevels" if args.wiper else "auditReadOnlyCapabilities"
    emulator = subprocess.check_output(["adb", "shell", "getprop", "ro.kernel.qemu"], text=True).strip()
    if emulator != "1":
        raise RuntimeError("Signal injection is restricted to an Android emulator")
    subprocess.run(["adb", "forward", "tcp:8088", "tcp:8088"], check=True, capture_output=True)
    stopped = threading.Event()
    original = request("/vehicle/state")
    user = subprocess.check_output(["adb", "shell", "am", "get-current-user"], text=True).strip()

    def heartbeat():
        while not stopped.is_set():
            request("/browser/session?client=chagok-sdk-probe", "POST")
            stopped.wait(0.75)

    thread = threading.Thread(target=heartbeat, daemon=True)
    thread.start()
    lines, injections = [], []
    process = None
    try:
        command = ["adb", "shell", "am", "instrument", "--user", user, "-w", "-e", "sdkProbe", "true",
                   "-e", "class", "io.chagok.app.CompanionSdkProbeTest#" + test_name,
                   "io.chagok.app.smoke.test/androidx.test.runner.AndroidJUnitRunner"]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
        # Bound a stuck instrumentation process without blocking the main control loop indefinitely.
        timeout = threading.Timer(50, process.kill)
        timeout.start()
        try:
            for line in process.stdout:
                lines.append(line)
                if "sdk_probe_phase=" not in line:
                    continue
                phase = line.strip().split("sdk_probe_phase=", 1)[1]
                if args.wiper:
                    mode = {"WIPER_OFF": "Off", "WIPER_LOW": "Low", "WIPER_HIGH": "High",
                            "WIPER_AUTO": "Auto", "WIPER_END": "Off"}[phase]
                    request("/vehicle/body?front_wiper=" + mode, "PUT")
                    injections.append({"phase": phase, "requestedWiper": mode})
                    print(phase, flush=True)
                    continue
                moving = phase in ("LEFT_MOVE_WIPER_ON", "RIGHT")
                request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
                request("/vehicle/gear?gear=" + ("Drive" if moving else "Park"), "PUT")
                request("/vehicle/controls?speed=" + ("36" if moving else "0") + "&accelerator=0&brake=0", "PUT")
                request("/vehicle/body?front_wiper=" + ("Low" if moving else "Off"), "PUT")
                turn = "BlinkLeft" if phase == "LEFT_MOVE_WIPER_ON" else "BlinkRight" if phase == "RIGHT" else "Off"
                request("/vehicle/turn-signal?mode=" + turn, "PUT")
                injections.append({"phase": phase, "requestedSpeedKmh": 36 if moving else 0,
                                   "requestedWiper": "Low" if moving else "Off", "requestedTurn": turn})
                print(phase, flush=True)
            process.wait()
        finally:
            timeout.cancel()
        output = "".join(lines)
        (OUT / (report_name + "-instrumentation.log")).write_text(output, encoding="utf-8")
        if process.returncode or "FAILURES!!!" in output or "OK (" not in output:
            raise RuntimeError("Instrumentation failed; inspect " + report_name + "-instrumentation.log")
        report = subprocess.check_output(["adb", "shell", "run-as", "io.chagok.app.smoke", "--user", user,
                                          "cat", "files/" + device_report], text=True, encoding="utf-8")
        data = json.loads(report)
        data["consoleInputs"] = injections
        (OUT / (report_name + ".json")).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        for item in data["events"]:
            if item["kind"] != "speed.event":
                print(item["kind"], item["value"])
        speed_events = [e["value"]["mps"] for e in data["events"] if e["kind"] == "speed.event"]
        if not args.wiper:
            print("Speed event values (m/s):", sorted(set(speed_events)))
    finally:
        if process is not None and process.poll() is None:
            process.kill()
            process.wait(timeout=5)
        try:
            request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
            request("/vehicle/gear?gear=Park", "PUT")
            mode = urllib.parse.quote(original["body"]["wipers"]["front"]["mode"])
            request("/vehicle/body?front_wiper=" + mode, "PUT")
            turn = urllib.parse.quote(original["lights"]["turn_signal_mode"])
            request("/vehicle/turn-signal?mode=" + turn, "PUT")
        finally:
            stopped.set()
            thread.join(timeout=3)


if __name__ == "__main__":
    main()
