"""Capture the parked diary beside native Pleos navigation, using the isolated smoke app."""
import argparse
import importlib.util
import pathlib
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("split_demo", ROOT / "tools/companion-split-demo.py")
split = importlib.util.module_from_spec(spec)
spec.loader.exec_module(split)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", action="store_true", help="Run the layout smoke test first to create labelled test records; resets only the .smoke database")
    parser.add_argument("--auto-diary", action="store_true", help="Show the v10 automatic drive history and recording settings")
    parser.add_argument("--evidence-dir", type=pathlib.Path, default=ROOT / "doc/design/companion-v8/android-evidence")
    args = parser.parse_args()
    if split.adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("This demonstration requires the Pleos emulator")
    if args.seed:
        subprocess.run([sys.executable, str(ROOT / "tools/smoke-device.py"), "--class",
                        ("io.chagok.app.AutoDiarySmokeTest#driveHistorySettingsAndMissingCostCanBeUsedInEveryPane" if args.auto_diary else "io.chagok.app.ParkedDiarySmokeTest#supportingPagesFitSmallPaneFullWidthAndNightMode")], check=True)
    output = args.evidence_dir.resolve(); output.mkdir(parents=True, exist_ok=True)
    user = split.adb("shell", "am", "get-current-user")
    split.adb("shell", "am", "force-stop", "--user", user, "io.chagok.app")
    split.adb("forward", "tcp:8088", "tcp:8088")
    stopped = threading.Event()
    def heartbeat():
        while not stopped.is_set():
            try: split.request("/browser/session?client=chagok-parked-demo", "POST")
            except Exception: pass
            stopped.wait(.75)
    thread = threading.Thread(target=heartbeat, daemon=True); thread.start()
    record = None
    try:
        time.sleep(1)
        split.request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
        split.request("/vehicle/gear?gear=Park", "PUT")
        split.adb("shell", "am", "start", "--user", user, "-n", "io.chagok.app.smoke/io.chagok.app.ui.MainActivity")
        time.sleep(2); split.arrange(user)
        def tap(description):
            for attempt in range(4):
                split.adb("shell", "uiautomator", "dump", "/sdcard/chagok-parked-ui.xml")
                tree = ET.fromstring(split.adb("shell", "cat", "/sdcard/chagok-parked-ui.xml"))
                nodes = [n for n in tree.iter("node") if n.get("package") == "io.chagok.app.smoke" and (n.get("content-desc") == description or n.get("text") == description) and n.get("enabled") == "true"]
                if nodes: break
                scrolls = [n for n in tree.iter("node") if n.get("package") == "io.chagok.app.smoke" and n.get("scrollable") == "true"]
                if len(scrolls) != 1 or attempt == 3: break
                x1,y1,x2,y2 = map(int, re.findall(r"\d+", scrolls[0].get("bounds")))
                split.adb("shell", "input", "swipe", (x1+x2)//2, y2-50, (x1+x2)//2, y1+50, 500)
                time.sleep(.5)
            if len(nodes) != 1: raise RuntimeError("Expected one available control: " + description)
            x1,y1,x2,y2 = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
            split.adb("shell", "input", "tap", (x1+x2)//2, (y1+y2)//2)
            time.sleep(1.5)
        record = subprocess.Popen(["adb", "shell", "screenrecord", "--size", "1920x1080", "--bit-rate", "8M", "--time-limit", "50", "/sdcard/chagok-parked-pages.mp4"])
        if args.auto_diary:
            for label,name in (("차량 요약 탭","summary"),("오늘의 드라이브","drives"),("더보기","more"),("자동 기록 설정","recording")):
                tap(label)
                (output / ("native-auto-"+name+".png")).write_bytes(subprocess.check_output(["adb","exec-out","screencap","-p"]))
                print("Captured",label,flush=True);time.sleep(2)
        else:
            for label, name in (("차량 요약", "summary"), ("차량 일지", "diary"), ("소모품 관리", "maintenance"), ("비용 통계", "statistics")):
                tap(label + " 탭")
                (output / ("native-parked-" + name + ".png")).write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"]))
                print("Captured", label, flush=True)
                time.sleep(2)
        tap("주행 화면으로 돌아가기")
        (output / "native-companion-entry.png").write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"]))
        print("Returned to the driving companion. Diary pages remain optional parked destinations.", flush=True)
    finally:
        if record:
            record.wait(timeout=55)
            split.adb("pull", "/sdcard/chagok-parked-pages.mp4", str(output / "native-parked-pages.mp4"))
        stopped.set(); thread.join(timeout=3)


if __name__ == "__main__":
    main()
