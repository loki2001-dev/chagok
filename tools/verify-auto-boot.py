"""Verify opted-in recording after a real emulator reboot. Never reset a database."""
import datetime
import json
import pathlib
import sqlite3
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "doc/design/auto-diary-v10"
PKG = "io.chagok.app.smoke"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, encoding="utf-8", timeout=15).strip()


def settings(user):
    target = ROOT / ".dev/boot-probe.db"
    target.write_bytes(subprocess.check_output(["adb", "exec-out", "run-as", PKG, "--user", user, "cat", "databases/chagok.db"], timeout=15))
    with sqlite3.connect(target) as db:
        return {k: json.loads(v)["v"] for k, v in db.execute("SELECT id,json FROM doc WHERE kind='setting'")}


def main():
    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Restricted to an emulator")
    user = adb("shell", "am", "get-current-user")
    adb("shell", "am", "start", "--user", user, "-n", PKG + "/io.chagok.app.ui.MainActivity")
    time.sleep(5)
    before = settings(user)
    if before.get("monitor.enabled") != "true":
        raise RuntimeError("Enable recording in the isolated smoke app first")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    print("Rebooting the emulator; the app will not be launched after boot.", flush=True)
    adb("reboot")
    time.sleep(4)
    end = time.monotonic() + 180
    last_error = ""
    while time.monotonic() < end:
        try:
            if adb("shell", "getprop", "sys.boot_completed") == "1" and adb("shell", "am", "get-current-user") == user:
                after = settings(user)
                services = adb("shell", "dumpsys", "activity", "services", PKG)
                event = after.get("monitor.lastStartEvent", "")
                fresh = bool(event and after.get("monitor.lastHealthy") and datetime.datetime.fromisoformat(after["monitor.lastHealthy"].replace("Z", "+00:00")) > datetime.datetime.fromisoformat(event.split("|")[1].replace("Z", "+00:00")))
                if event.startswith("android.intent.action.BOOT_COMPLETED|") and event != before.get("monitor.lastStartEvent") and "isForeground=true" in services and fresh:
                    report = {"verifiedAt": datetime.datetime.now().astimezone().isoformat(), "package": PKG,
                              "user": user, "rebootedEmulator": True, "appLaunchedAfterBoot": False,
                              "recordingEnabled": after.get("monitor.enabled"), "bootEvent": event,
                              "foregroundServiceResumed": True, "freshVehicleObservation": after.get("monitor.lastHealthy"), "lastStartError": after.get("monitor.startError")}
                    OUT.mkdir(parents=True, exist_ok=True)
                    (OUT / "boot-verification.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
                    (OUT / "boot-service.txt").write_text(services, encoding="utf-8")
                    print(json.dumps(report, ensure_ascii=False), flush=True)
                    return
        except (subprocess.SubprocessError, sqlite3.DatabaseError, ValueError) as error:
            last_error = str(error)
        time.sleep(3)
    raise RuntimeError("Boot recording did not resume: " + last_error)


if __name__ == "__main__":
    main()
