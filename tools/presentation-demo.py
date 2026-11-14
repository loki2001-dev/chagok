"""Prepare/play a live, isolated Pleos presentation. Fuel and spoken answers are scripted replay."""
import argparse
import importlib.util
import json
import pathlib
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "doc/demo/evidence"
APP = "io.chagok.app.smoke"
ACTIVITY = APP + "/io.chagok.app.companion.CompanionPresentationActivity"
spec = importlib.util.spec_from_file_location("split", ROOT / "tools/companion-split-demo.py")
split = importlib.util.module_from_spec(spec)
spec.loader.exec_module(split)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare", action="store_true", help="Reset only .smoke and leave the start screen ready")
    parser.add_argument("--rehearse", action="store_true", help="Run/verify all stages, then restore an empty ready screen")
    parser.add_argument("--landscape-rain", action="store_true", help="Wide Hyundai sedan, sustained high front wiper")
    args = parser.parse_args()
    rainy = args.landscape_rain
    output = OUT / "landscape-rain" if rainy else OUT
    if split.adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Presentation is restricted to the Android emulator")
    output.mkdir(parents=True, exist_ok=True)
    user = split.adb("shell", "am", "get-current-user")
    split.adb("shell", "am", "force-stop", "--user", user, "io.chagok.app")
    split.adb("forward", "tcp:8088", "tcp:8088")
    stopped = threading.Event()

    def heartbeat():
        while not stopped.is_set():
            try:
                split.request("/browser/session?client=chagok-presentation", "POST")
            except Exception:
                pass
            stopped.wait(.75)

    thread = threading.Thread(target=heartbeat, daemon=True)
    thread.start()

    def request(path):
        return split.request(path, "PUT")

    def park():
        request("/vehicle/controls?speed=0&accelerator=0&brake=0")
        request("/vehicle/gear?gear=Park")

    def speed(kmh):
        request(f"/vehicle/controls?speed={kmh}&accelerator=0&brake=0")

    def status():
        return json.loads(split.adb("exec-out", "run-as", APP, "--user", user, "cat", "files/presentation-status.json"))

    def wait_for(description, predicate, timeout=30):
        deadline = time.monotonic() + timeout
        latest = None
        while time.monotonic() < deadline:
            try:
                latest = status()
            except (subprocess.CalledProcessError, json.JSONDecodeError):
                time.sleep(.3)
                continue
            if latest.get("error"):
                raise RuntimeError(latest["error"])
            if predicate(latest):
                return latest
            time.sleep(.3)
        raise RuntimeError(f"Timed out waiting for {description}: {latest}")

    def command(name):
        # Starting an Activity again makes the Pleos shell reorder panes and briefly pause speech.
        split.adb("shell", "am", "broadcast", "--user", user, "-a", "io.chagok.app.smoke.PRESENTATION_COMMAND",
                  "-p", APP, "--es", "presentationCommand", name)
        print(name, flush=True)

    def screenshot(name):
        (output / (name + ".png")).write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"]))

    def ready_weather():
        # The real wiper policy needs 60 seconds of steady input. Recheck after an idle wait.
        request("/vehicle/body?front_wiper=" + ("High" if rainy else "Off"))
        if rainy:
            print("WIPER HIGH: waiting for confirmed native rain", flush=True)
            last_frames, last_change = None, time.monotonic()
            def raining(s):
                nonlocal last_frames, last_change
                now = time.monotonic()
                if s.get("frames") != last_frames:
                    last_frames, last_change = s.get("frames"), now
                elif now - last_change > 6:
                    # A cold Pleos window can briefly be reparented behind the home activity.
                    split.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
                    split.adb("shell", "am", "start", "--user", user, "-n", ACTIVITY, "--ez", "landscapeRain", "true")
                    arrangement()
                    last_change = time.monotonic()
                return (s.get("skin") == "HYUNDAI" and s.get("landscape") and s.get("rainConfirmed")
                        and s.get("rain", 0) > .9)
            wait_for("wide sedan and SDK-confirmed rain", raining, 150)

    def arrangement():
        if not rainy:
            # Keep the presentation as the second app even when an unrelated PLEOS task was open.
            split.adb("shell", "am", "start", "--user", user, "-n", split.NAV)
        for attempt in range(12):
            panes = split.panes()
            pane = panes.get(APP, {})
            nav = panes.get(split.NAV.split("/")[0], {})
            if not rainy:
                if nav.get("visible") and nav["left"] == 853 and pane.get("visible") and pane["left"] >= 1700 and pane["right"] <= 2560:
                    return panes
                if not pane.get("visible"):
                    split.adb("shell", "am", "start", "--user", user, "-n", ACTIVITY)
                elif not nav.get("visible") and pane["right"]-pane["left"] < 1000:
                    split.adb("shell", "am", "start", "--user", user, "-n", split.NAV)
                elif pane["right"]-pane["left"] > 1000 or nav.get("right", 0)-nav.get("left", 0) > 1000:
                    split.adb("shell", "input", "swipe", 2546, 720, 1650, 720, 1000)
                else:
                    split.adb("shell", "input", "tap", 1706, 720)
                time.sleep(2)
                continue
            if not pane.get("visible"):
                split.adb("shell", "am", "start", "--user", user, "-n", ACTIVITY, "--ez", "landscapeRain", "true")
            if pane.get("visible") and pane["right"]-pane["left"] >= 1300 and pane["top"] == 90:
                return panes
            if pane.get("visible") and pane["right"]-pane["left"] < 1000:
                target = 860 if pane["left"] >= 1700 else 2550
                split.adb("shell", "input", "swipe", 1706, 720, target, 720, 850)
            time.sleep(1)
        raise RuntimeError("Pleos did not arrange the presentation: " + str(split.panes()))

    def prepare():
        park()
        if not split.panes().get('ai.umos.drivingview', {}).get('visible'):
            split.adb('shell', 'am', 'start', '--user', user, '-a', 'android.intent.action.MAIN',
                      '-c', 'android.intent.category.HOME', '-n', 'ai.umos.homescreen/.HomeActivity')
            until = time.monotonic() + 30
            while not split.panes().get('ai.umos.drivingview', {}).get('visible'):
                if time.monotonic() >= until:
                    raise RuntimeError('Pleos window manager is not ready after boot')
                time.sleep(.5)
        request("/vehicle/turn-signal?mode=Off")
        request("/vehicle/body?front_wiper=" + ("High" if rainy else "Off"))
        split.adb("shell", "am", "force-stop", "--user", user, APP)
        vehicle = split.request("/vehicle/state")
        km = max(47200, vehicle["odometer"]["traveled_distance_km"] or 0)
        request(f"/vehicle/odometer?traveled_distance_km={km}")
        split.adb("shell", "am", "start", "--user", user, "-n", ACTIVITY, "--ez", "resetPresentation", "true",
                  "--ez", "landscapeRain", str(rainy).lower())
        time.sleep(1)
        panes = arrangement()
        pane = panes[APP]
        if not rainy:
            split.adb("shell", "input", "tap", (pane["left"] + pane["right"]) // 2, 500)
        ready_weather()
        ready = wait_for("stable fuel baseline and fresh parked SDK", lambda s: s["phase"] == "READY" and s.get("baseline") == 30 and s.get("parked") and abs(s["speedKmh"]) < .1)
        (output / "ready.json").write_text(json.dumps({"status": ready, "panes": panes}, ensure_ascii=False, indent=2), encoding="utf-8")
        screenshot("ready")
        print("READY:", ready.get("skin"), "wide rain" if rainy else "portrait dry", flush=True)

    try:
        time.sleep(1)
        if args.prepare:
            prepare()
            return
        try:
            current = status()
        except Exception:
            current = {}
        pane = split.panes().get(APP, {})
        if current.get("phase") != "READY" or current.get("confirmedCount") != 0 or not pane.get("visible") or (
                current.get("skin") != ("HYUNDAI" if rainy else "RACER")
                or bool(current.get("landscape")) != rainy
                or (not rainy and pane.get("left", 0) < 1700)):
            prepare()
        park()
        ready_weather()
        wait_for("ready SDK", lambda s: s.get("parked") and s.get("baseline") == 30 and abs(s["speedKmh"]) < .1)
        start = time.monotonic()
        start_km = split.request("/vehicle/state")["odometer"]["traveled_distance_km"]
        points = []

        def distance(km):
            request(f"/vehicle/odometer?traveled_distance_km={start_km+km:.2f}")

        def checkpoint(label):
            item = status(); item["checkpoint"] = label; item["runSeconds"] = round(time.monotonic()-start, 2)
            if rainy:
                assert item["skin"] == "HYUNDAI" and item["landscape"] and item["rainConfirmed"] and item["rain"] > .9, item
            points.append(item); screenshot(label)

        command("DRIVE")
        request("/vehicle/gear?gear=Drive"); speed(35)
        wait_for("driving SDK", lambda s: s["speedKmh"] > 30)
        time.sleep(3); distance(.05); checkpoint("01-driving")
        command("LEFT"); request("/vehicle/turn-signal?mode=BlinkLeft")
        wait_for("left turn", lambda s: s.get("turn") == -1)
        time.sleep(3); distance(.12); checkpoint("02-left")
        command("RIGHT"); request("/vehicle/turn-signal?mode=BlinkRight")
        wait_for("right turn", lambda s: s.get("turn") == 1)
        time.sleep(3); distance(.20); checkpoint("03-right")
        request("/vehicle/turn-signal?mode=Off"); command("ACCELERATE")
        for value in (45, 55, 65, 75, 85):
            speed(value); time.sleep(.45)
        if rainy:
            wait_for("sedan acceleration", lambda s: s["speedKmh"] >= 80 and s["maxBoost"] == 0)
            assert points[1]["carY"] < points[2]["carY"], "Expected left upper and right lower lane"
        else:
            wait_for("native booster", lambda s: s["maxBoost"] > .15)
        distance(.35); checkpoint("04-accelerate" if rainy else "04-boost"); time.sleep(2)
        for value in (65, 40, 15, 0):
            speed(value); time.sleep(.5)
        distance(.45); park(); command("PARK")
        wait_for("parked SDK", lambda s: s.get("parked") and abs(s["speedKmh"]) < .1)
        time.sleep(2); command("REFUEL")
        wait_for("real detector's 30 to 80 fuel candidate", lambda s: s["candidateCount"] == 1 and s.get("collecting") is True, timeout=25)
        checkpoint("05-refuel")
        command("RESTART"); request("/vehicle/gear?gear=Drive"); speed(35)
        wait_for("question after restart", lambda s: s["phase"] == "QUESTION" and s["stage"] == "AMOUNT")
        distance(.60); time.sleep(3); checkpoint("06-question")
        command("AMOUNT")
        wait_for("amount confirmation", lambda s: s["stage"] == "CONFIRM" and s["monthTotal"] == 0)
        time.sleep(3); checkpoint("07-confirm")
        command("SAVE")
        # The native activity opens the real summary 2 seconds after save, once Park is observed.
        park()
        result = wait_for("summary after save", lambda s: s["phase"] == "SUMMARY", timeout=12)
        assert result["confirmedCount"] == 1 and result["monthTotal"] == 50000
        delay = result["summaryAtMs"] - result["savedAtMs"]
        assert 1900 <= delay <= 5000, delay
        time.sleep(2)
        recovered = False
        for attempt in range(5):
            split.adb("shell", "uiautomator", "dump", "/sdcard/chagok-presentation-summary.xml")
            xml = split.adb("shell", "cat", "/sdcard/chagok-presentation-summary.xml")
            texts = [n.get("text", "") for n in ET.fromstring(xml).iter("node") if n.get("package") == APP]
            if all(label in texts for label in ("차량 요약", "50,000원", "주유")):
                break
            if attempt == 1:
                split.adb("shell", "am", "start", "--user", user, "-n", APP + "/io.chagok.app.ui.MainActivity")
                recovered = True
            time.sleep(.6)
        else:
            raise RuntimeError("Saved refuel was not visible in the native summary")
        screenshot("08-summary")
        (output / "summary-ui.xml").write_text(xml, encoding="utf-8")
        report = {"passed": True, "scene": "landscape-rain" if rainy else "portrait", "summaryRefocused": recovered, "summaryDelayMs": delay, "durationSeconds": round(time.monotonic()-start, 2),
                  "result": result, "checkpoints": points,
                  "scope": "Real SDK speed/gear/turn/odometer; scripted fuel and STT-result replay; real detector, conversation, SQLite and summary. Emulator-only moving-question/animation override."}
        (output / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"PASS: one 50,000 KRW refuel entry, summary after {delay} ms", flush=True)
        if args.rehearse:
            prepare()
        elif rainy:
            request("/vehicle/body?front_wiper=Off")
    finally:
        try:
            park(); request("/vehicle/turn-signal?mode=Off")
        finally:
            stopped.set(); thread.join(timeout=3)


if __name__ == "__main__":
    main()
