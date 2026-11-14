"""Run isolated Android instrumentation with Pleos Virtual Console signals. Standard library only."""
import json
import http.client
import urllib.error
import argparse
import pathlib
import re
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOG = ROOT / ".dev" / "smoke-device.log"
BASE = "http://127.0.0.1:8088"


def request(path, method="GET"):
    # The local Virtual Console can reset a connection while activities are reparented.
    # These reads and target-value setters are idempotent; HTTP validation errors still fail immediately.
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(BASE + path, method=method), timeout=8) as response:
                return json.load(response)
        except urllib.error.HTTPError:
            raise
        except (urllib.error.URLError, http.client.RemoteDisconnected, TimeoutError):
            if attempt == 2:
                raise
            time.sleep(.25 * (attempt + 1))


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--class", dest="test_class", help="Optional instrumentation class or class#method")
    parser.add_argument("--workshop-visit", action="store_true",
                        help="Drive to a synthetic repair shop, park for 60s, save a belt replacement and verify the dashboard")
    args = parser.parse_args()
    if args.workshop_visit and args.test_class:
        parser.error("--workshop-visit selects its own instrumentation class")
    if subprocess.check_output(["adb", "shell", "getprop", "ro.kernel.qemu"], text=True).strip() != "1":
        raise RuntimeError("Signal injection is restricted to an Android emulator")
    current_user = subprocess.check_output(["adb", "shell", "am", "get-current-user"], text=True).strip()
    # Simulated vehicle signals are global. Stop the normal app's collector before injecting them.
    subprocess.run(["adb", "shell", "am", "force-stop", "--user", current_user, "io.chagok.app"], check=True)
    subprocess.run(["adb", "shell", "am", "force-stop", "--user", current_user, "io.chagok.app.smoke"], check=True)
    subprocess.run(["adb", "forward", "tcp:8088", "tcp:8088"], check=True, capture_output=True)
    stopped = threading.Event()

    def heartbeat():
        while not stopped.is_set():
            try:
                request("/browser/session?client=chagok-smoke", "POST")
            except Exception:
                pass
            stopped.wait(0.75)

    thread = threading.Thread(target=heartbeat, daemon=True)
    thread.start()
    original = None
    process = None
    watchdog = None
    gps_thread = None
    departed = threading.Event()
    old_gps = None
    def geo_fix(longitude, latitude):
        result = subprocess.check_output(["adb", "emu", "geo", "fix", str(longitude), str(latitude)], text=True)
        if result.strip() != "OK":
            raise RuntimeError("Emulator GPS injection failed: " + result.strip())
    try:
        time.sleep(1)
        original = request("/vehicle/state")
        # Virtual Console rejects decreasing the odometer, so keep any existing higher value.
        expected_km = int(max(47200, original["odometer"]["traveled_distance_km"] or 0))
        request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
        request("/vehicle/gear?gear=Park", "PUT")
        command = ["adb", "shell", "am", "instrument", "--user", current_user, "-w", "-e", "virtualConsole", "true", "-e", "expectedKm", str(expected_km),
                   "io.chagok.app.smoke.test/androidx.test.runner.AndroidJUnitRunner"]
        if args.test_class:
            command[-1:-1] = ["-e", "class", args.test_class]
        if args.workshop_visit:
            command[-1:-1] = ["-e", "workshopVisit", "true", "-e", "class", "io.chagok.app.WorkshopVisitIntegrationTest"]
            locations = subprocess.check_output(["adb", "shell", "dumpsys", "location"], text=True, encoding="utf-8")
            last_gps = re.search(r"last location=Location\[gps ([-\d.]+),([-\d.]+)", locations)
            if last_gps:
                old_gps = (last_gps[2], last_gps[1])
            geo_fix(127.0, 37.51)
        lines = []
        with LOG.open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
            watchdog = threading.Timer(600, process.kill)
            watchdog.start()
            for line in process.stdout:
                log.write(line); log.flush(); lines.append(line)
                if "chagok_signal=" in line:
                    signal = line.strip().split("chagok_signal=", 1)[1]
                    print("Virtual Console:", signal, flush=True)
                    if signal == "WORKSHOP_APPROACH" and args.workshop_visit:
                        def stream_gps():
                            while not stopped.is_set():
                                geo_fix(127.0, 37.51 if departed.is_set() else 37.5)
                                stopped.wait(2)
                        gps_thread = threading.Thread(target=stream_gps, daemon=True)
                        gps_thread.start()
                    elif signal == "WORKSHOP_EXIT" and args.workshop_visit:
                        departed.set()
                    if signal == "RESET":
                        request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
                        request("/vehicle/gear?gear=Park", "PUT")
                        request("/vehicle/body?front_wiper=Off", "PUT")
                        request("/vehicle/turn-signal?mode=Off", "PUT")
                    elif signal == "BASELINE":
                        request("/vehicle/odometer?traveled_distance_km=" + str(expected_km), "PUT")
                        request("/vehicle/ev-battery?field=soc&value=30", "PUT")
                    elif signal == "RISE":
                        request("/vehicle/ev-battery?field=soc&value=80", "PUT")
                    elif signal == "TRIP_DISTANCE":
                        request("/vehicle/odometer?traveled_distance_km=" + str(expected_km + 1), "PUT")
                    elif signal == "DRIVE":
                        request("/vehicle/gear?gear=Drive", "PUT")
                        request("/vehicle/controls?speed=25&accelerator=0&brake=0", "PUT")
                    elif signal == "PARK":
                        request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
                        request("/vehicle/gear?gear=Park", "PUT")
                    elif signal.startswith("WIPER_"):
                        mode = {"WIPER_OFF": "Off", "WIPER_LOW": "Low", "WIPER_HIGH": "High"}[signal]
                        request("/vehicle/body?front_wiper=" + mode, "PUT")
                    elif signal.startswith("TURN_"):
                        mode = {"TURN_OFF": "Off", "TURN_LEFT": "BlinkLeft", "TURN_RIGHT": "BlinkRight"}[signal]
                        request("/vehicle/turn-signal?mode=" + mode, "PUT")
            process.wait()
        output = "".join(lines)
        print(output[-2000:])
        if process.returncode or "FAILURES!!!" in output or "OK (" not in output:
            raise SystemExit("Smoke tests failed. See " + str(LOG))
    finally:
        if watchdog:
            watchdog.cancel()
        if process is not None and process.poll() is None:
            process.kill(); process.wait(timeout=5)
        subprocess.run(["adb", "shell", "am", "force-stop", "--user", current_user, "io.chagok.app.smoke"], check=False)
        try:
            request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
            request("/vehicle/gear?gear=Park", "PUT")
            if original:
                request("/vehicle/ev-battery?field=soc&value=" + str(original["ev_battery"]["state_of_charge"]), "PUT")
                request("/vehicle/body?front_wiper=" + urllib.parse.quote(original["body"]["wipers"]["front"]["mode"]), "PUT")
                request("/vehicle/turn-signal?mode=" + urllib.parse.quote(original["lights"]["turn_signal_mode"]), "PUT")
            time.sleep(1)
        finally:
            stopped.set(); thread.join(timeout=3)
            if gps_thread:
                gps_thread.join(timeout=5)
            if old_gps:
                geo_fix(*old_gps)


if __name__ == "__main__":
    main()
