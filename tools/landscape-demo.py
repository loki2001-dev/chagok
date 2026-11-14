"""Record the native horizontal companion in the isolated, labelled Pleos preview APK."""
import importlib.util
import json
import pathlib
import subprocess
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "doc/design/landscape-v11/android-evidence"
spec = importlib.util.spec_from_file_location("split_demo", ROOT / "tools/companion-split-demo.py")
split = importlib.util.module_from_spec(spec)
spec.loader.exec_module(split)


def main():
    if split.adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("This preview requires the Pleos emulator")
    OUT.mkdir(parents=True, exist_ok=True)
    user = split.adb("shell", "am", "get-current-user")
    split.adb("shell", "am", "force-stop", "--user", user, "io.chagok.app")
    split.adb("shell", "am", "force-stop", "--user", user, "io.chagok.app.smoke")
    split.adb("forward", "tcp:8088", "tcp:8088")
    stopped = threading.Event()

    def heartbeat():
        while not stopped.is_set():
            try:
                split.request("/browser/session?client=chagok-landscape-demo", "POST")
            except Exception:
                pass
            stopped.wait(.75)

    thread = threading.Thread(target=heartbeat, daemon=True)
    thread.start()
    record = None
    try:
        time.sleep(1)
        split.request("/vehicle/controls?speed=0&accelerator=0&brake=0", "PUT")
        split.request("/vehicle/gear?gear=Park", "PUT")
        split.adb("shell", "am", "start", "--user", user, "-n", split.PROBE, "--ez", "showcase", "true")
        time.sleep(2)
        pane = split.panes()["io.chagok.app.smoke"]
        if not pane["visible"] or pane["right"] - pane["left"] < 1200:
            raise RuntimeError("Expand the smoke application to the native Pleos wide pane first")
        (OUT / "native-window.json").write_text(json.dumps(pane, indent=2), encoding="utf-8")
        split.adb("shell", "dumpsys", "gfxinfo", "io.chagok.app.smoke", "reset")
        record = subprocess.Popen(["adb", "shell", "screenrecord", "--size", "1920x1080", "--bit-rate", "8M", "--time-limit", "42", "/sdcard/chagok-landscape-preview.mp4"])
        start = time.monotonic()
        for second, name in ((4, "racer-upper"), (8, "racer-lower"), (17, "racer-rain"), (28, "hyundai-upper"), (32, "hyundai-lower"), (39, "hyundai-rain")):
            time.sleep(max(0, start + second - time.monotonic()))
            (OUT / ("native-" + name + ".png")).write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"]))
            print("Captured " + name, flush=True)
        (OUT / "native-gfxinfo.txt").write_text(split.adb("shell", "dumpsys", "gfxinfo", "io.chagok.app.smoke"), encoding="utf-8")
    finally:
        if record:
            record.wait(timeout=50)
            split.adb("pull", "/sdcard/chagok-landscape-preview.mp4", str(OUT / "native-landscape-preview.mp4"))
        split.adb("shell", "am", "force-stop", "--user", user, "io.chagok.app.smoke")
        stopped.set()
        thread.join(timeout=3)


if __name__ == "__main__":
    main()
