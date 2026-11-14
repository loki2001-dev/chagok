"""Capture the README's labelled scenario in the isolated .smoke Android app.

Requires an already running Pleos emulator, adb on PATH, and installed smoke + test APKs.
Run after tools/dev-env.ps1 and the build/install commands documented in README.md.
"""
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "doc" / "readme"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    images = OUT / "images"
    images.mkdir(exist_ok=True)
    try:
        subprocess.run([sys.executable, str(ROOT / "tools" / "smoke-device.py"),
                        "--class", "io.chagok.app.ReadmeScenarioCaptureTest"], cwd=ROOT, check=True)
        user = subprocess.check_output(["adb", "shell", "am", "get-current-user"], text=True).strip()

        def pull(name):
            return subprocess.check_output(["adb", "exec-out", "run-as", "io.chagok.app.smoke",
                                            "--user", user, "cat", "files/" + name])

        metadata = pull("readme-scenario.json")
        scenario = json.loads(metadata)
        for name in scenario["images"]:
            (images / name).write_bytes(pull("readme-" + name))
        (OUT / "scenario.json").write_bytes(metadata)
        print(f"Captured {len(scenario['images'])} native Android screens in {images}")
    finally:
        log = ROOT / ".dev" / "smoke-device.log"
        if log.exists():
            shutil.copyfile(log, OUT / "capture-result.txt")
        # The signal runner restores Park and stops .smoke before returning.
        subprocess.run(["adb", "shell", "am", "start", "-n", "io.chagok.app/.companion.CompanionActivity"], check=False)


if __name__ == "__main__":
    main()
