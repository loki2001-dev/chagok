#!/usr/bin/env bash
# Assemble two recorded emulator scenarios without changing the vehicle or either app's diary.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
source_dir="${1:-$root/.dev/service-captures}"
output="${2:-$root/demo/chagok-fuel-workshop.mp4}"
python_bin="${PYTHON:-python3}"
mkdir -p "$(dirname "$output")"
out_dir=$(cd "$(dirname "$output")" && pwd)
output="$out_dir/$(basename "$output")"
fuel=$(cd "$source_dir" && pwd)/refuel-wide.mp4
repair="$root/doc/demo/workshop-visit.mp4"
summary="$root/doc/demo/evidence/landscape-rain/08-summary.png"
for file in "$fuel" "$repair" "$summary"; do
    [[ -s "$file" ]] || { echo "Missing recording or screenshot: $file" >&2; exit 1; }
done
tmp=$(mktemp -d)
trap 'rm -f "$tmp"/{bold.ttf,regular.ttf,caption.ass,chapter1.mp4,chapter2.mp4,fuel.mp4,summary.mp4,repair.mp4,playlist.txt}; rmdir "$tmp"' EXIT
cd "$tmp"
if [[ -f /c/Windows/Fonts/malgun.ttf ]]; then
    cp /c/Windows/Fonts/malgun.ttf regular.ttf
    cp /c/Windows/Fonts/malgunbd.ttf bold.ttf
    font='Malgun Gothic'
else
    font='NanumSquare_ac'
    cp /usr/share/fonts/truetype/nanum/NanumSquare_acR.ttf regular.ttf
    cp /usr/share/fonts/truetype/nanum/NanumSquare_acB.ttf bold.ttf
fi
export PYTHONUTF8=1
# Reuse the title cards and timed Korean subtitles from the existing demo renderer.
"$python_bin" - "$root/tools/demo-render.py" "$font" <<'PY'
import runpy, sys
from pathlib import Path
module = runpy.run_path(sys.argv[1])
card = module["card"]
card.__globals__.update(WIDTH=1440, HEIGHT=900, FPS=24,
                        FONT_BOLD_FILE="bold.ttf", FONT_REGULAR_FILE="regular.ttf", FONT=sys.argv[2])
for path, chapter, title, description in (
    ("chapter1.mp4","차곡   /   주유","주유비는 얼마였어요?","주유소에 들러 연료를 채우고, 다시 길을 나섭니다."),
    ("chapter2.mp4","차곡   /   정비","방금 정비하셨나요?","정비소를 떠난 뒤, 두 번의 답으로 정비 기록을 남깁니다."),
):
    card(Path(path), 3, [(chapter, 30, "0x67D8B4", 300), (title, 74, "0xF4F8F5", 365),
                         (description, 28, "0xC7D2CE", 480)])
PY

caption() {
    "$python_bin" - "$root/tools/demo-render.py" "$font" "$@" <<'PY'
import runpy, sys
from pathlib import Path
module = runpy.run_path(sys.argv[1])
build = module["build_ass"]
build.__globals__.update(WIDTH=1440, HEIGHT=900, FONT=sys.argv[2], CAPTION_HOLD=1000)
values = sys.argv[4:]
rows = [(float(t), caption) for t, caption in zip(values[::2],values[1::2])]
Path("caption.ass").write_text(build(rows,float(sys.argv[3])),encoding="utf-8")
PY
}
encode=(-an -c:v libx264 -preset veryfast -crf 21 -pix_fmt yuv420p -r 24)
layout='fps=24,scale=1440:810,setsar=1,pad=1440:900:0:0:color=0x101318,subtitles=caption.ass:fontsdir=.'
caption 18.5 0 '현대 세단이 주유소에 들어가 정차합니다.' 6 '연료 잔량이 올랐습니다. 다시 출발하면 주유비를 물어봅니다.' 13.5 '“5만 원.” 금액을 확인하고 주유 기록을 저장합니다.'
ffmpeg -y -loglevel error -ss 10.0 -t 18.5 -i "$fuel" -vf "$layout" "${encode[@]}" fuel.mp4
caption 4 0 '차량 요약에 주유 1건, 50,000원이 남았습니다.'
ffmpeg -y -loglevel error -loop 1 -framerate 24 -i "$summary" -t 4 -vf "$layout" "${encode[@]}" summary.mp4
caption 33.8 0 '정비소에서 60초 이상 머무는 동안에는 질문을 보류합니다.' 4.5 '정비소에서 150m 밖으로 벗어나자 첫 질문이 뜹니다.' 10 '“네.” 이어서 무엇을 정비했는지 묻습니다.' 13 '“드라이브 벨트 교체, 9만 5천 원.” 답한 내용이 일지에 저장됩니다.' 17 '다시 정차하면 정비소 입차와 벨트 교체 장면이 재생됩니다.' 27 '차량 요약에서 다음 교체 예정일을 확인합니다.' 30.6 '기록 상세에 정비소와 벨트 교체 비용 95,000원이 남았습니다.'
ffmpeg -y -loglevel error -i "$repair" -vf "$layout" "${encode[@]}" repair.mp4
printf "file '%s'\n" chapter1.mp4 fuel.mp4 summary.mp4 chapter2.mp4 repair.mp4 > playlist.txt
ffmpeg -y -loglevel error -f concat -safe 0 -i playlist.txt -c copy -movflags +faststart "$output"
echo "Rendered $output"
