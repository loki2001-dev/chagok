#!/usr/bin/env python3
"""차곡 시연 영상 합성.

demo/raw.webm 과 demo/captions.tsv (tools/demo-record.sh 결과)로
제목 화면 + 자막이 들어간 본편 + 마무리 화면을 이어 붙여 demo/chagok-demo.mp4 를 만들고,
README에서 바로 재생되는 demo/chagok-demo.gif 를 함께 만든다.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEMO = ROOT / "demo"
RAW = DEMO / "raw.webm"
CAPTIONS = DEMO / "captions.tsv"
MP4 = DEMO / "chagok-demo.mp4"
GIF = DEMO / "chagok-demo.gif"

FONT = "NanumSquare_ac"
FONT_BOLD_FILE = "/usr/share/fonts/truetype/nanum/NanumSquare_acB.ttf"
FONT_REGULAR_FILE = "/usr/share/fonts/truetype/nanum/NanumSquare_acR.ttf"
WIDTH, HEIGHT, FPS = 1408, 792, 30
CAPTION_HOLD = 8.0  # 다음 자막이 없을 때 자막을 보여 줄 최대 시간
GIF_WIDTH, GIF_FPS = 880, 8


def run(args):
    print("+", " ".join(str(a) for a in args[:6]), "...")
    subprocess.run(args, check=True)


def ass_time(seconds):
    seconds = max(0.0, seconds)
    h = int(seconds // 3600)
    m = int(seconds % 3600 // 60)
    s = seconds % 60
    return f"{h}:{m:02d}:{s:05.2f}"


def build_ass(rows, duration):
    header = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {WIDTH}
PlayResY: {HEIGHT}
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Step,{FONT},26,&H00FFFFFF,&H00FFFFFF,&H00000000,&HB0140D0A,1,0,0,0,100,100,0,0,3,14,0,2,60,60,28,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
    events = []
    for i, (t, text) in enumerate(rows):
        nxt = rows[i + 1][0] if i + 1 < len(rows) else duration
        end = min(nxt, t + CAPTION_HOLD, duration)
        events.append(f"Dialogue: 0,{ass_time(t)},{ass_time(end - 0.1)},Step,,0,0,0,,{text}")
    return header + "\n".join(events) + "\n"


def probe_duration(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(path)],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    return float(out)


def card(path, seconds, lines):
    """어두운 배경 제목 화면. lines = [(text, size, color, y)]"""
    filters = []
    for text, size, color, y in lines:
        font = FONT_BOLD_FILE if size >= 40 else FONT_REGULAR_FILE
        safe = text.replace(":", r"\:").replace("'", r"\'")
        filters.append(
            f"drawtext=fontfile={font}:text='{safe}':fontsize={size}:fontcolor={color}:x=(w-text_w)/2:y={y}"
            f":alpha='if(lt(t,0.6),t/0.6,1)'"
        )
    run([
        "ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi",
        "-i", f"color=c=0x101318:s={WIDTH}x{HEIGHT}:r={FPS}:d={seconds}",
        "-vf", ",".join(filters), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-r", str(FPS), str(path),
    ])


def main():
    if not RAW.exists() or not CAPTIONS.exists():
        sys.exit("demo/raw.webm or demo/captions.tsv missing. run tools/demo-record.sh first")
    rows = []
    for line in CAPTIONS.read_text(encoding="utf8").splitlines():
        t, text = line.split("\t", 1)
        rows.append((float(t), text))

    duration = probe_duration(RAW)
    ass = DEMO / ".captions.ass"
    ass.write_text(build_ass(rows, duration), encoding="utf8")

    body = DEMO / ".body.mp4"
    run([
        "ffmpeg", "-y", "-loglevel", "error", "-i", str(RAW),
        "-vf", f"scale={WIDTH}:{HEIGHT},fps={FPS},subtitles={ass}",
        "-c:v", "libx264", "-preset", "medium", "-crf", "22", "-pix_fmt", "yuv420p", "-an", str(body),
    ])

    intro = DEMO / ".intro.mp4"
    card(intro, 4, [
        ("차곡", 80, "0x4CC9A4", 260),
        ("저절로 정리되고, 관리가 익숙해지는 차량 일지", 32, "0xF2F4F7", 370),
        ("Pleos Connect Emulator 실동작 시연  ·  차량 값은 Virtual Console 주입", 22, "0xB4BDC9", 440),
    ])
    outro = DEMO / ".outro.mp4"
    card(outro, 5, [
        ("한마디 → 날짜 · 주행거리 · 비용 → 다음 관리 → 관리 도감", 32, "0xF2F4F7", 250),
        ("Pleos Vehicle SDK (CAR_DRIVE · CAR_ENERGY)  ·  Pleos SpeechToText ON_DEVICE  ·  AAOS CarUxRestrictions", 20, "0xB4BDC9", 330),
        ("사용자가 확인한 것만 저장  ·  미수신 값은 0으로 채우지 않음  ·  후보는 확인 전 비용에 미반영", 20, "0xB4BDC9", 370),
        ("차곡", 56, "0x4CC9A4", 450),
    ])

    concat = DEMO / ".concat.txt"
    concat.write_text("".join(f"file '{p}'\n" for p in (intro, body, outro)), encoding="utf8")
    run([
        "ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(concat),
        "-c:v", "libx264", "-preset", "medium", "-crf", "22", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(MP4),
    ])

    # GitHub README는 저장소 안의 mp4를 바로 재생하지 않으므로 GIF를 함께 만든다
    palette = DEMO / ".palette.png"
    run([
        "ffmpeg", "-y", "-loglevel", "error", "-i", str(MP4),
        "-vf", f"fps={GIF_FPS},scale={GIF_WIDTH}:-1:flags=lanczos,palettegen=max_colors=128:stats_mode=diff", str(palette),
    ])
    run([
        "ffmpeg", "-y", "-loglevel", "error", "-i", str(MP4), "-i", str(palette),
        "-lavfi", f"fps={GIF_FPS},scale={GIF_WIDTH}:-1:flags=lanczos[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle",
        str(GIF),
    ])

    for p in (body, intro, outro, concat, ass, palette):
        p.unlink(missing_ok=True)
    print(f"rendered {MP4} ({probe_duration(MP4):.1f}s, {MP4.stat().st_size / 1e6:.1f}MB)")
    print(f"rendered {GIF} ({GIF.stat().st_size / 1e6:.1f}MB)")


if __name__ == "__main__":
    main()
