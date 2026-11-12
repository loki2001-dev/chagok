#!/usr/bin/env bash
# 차곡 시연 녹화: 에뮬레이터 화면을 녹화하면서 전체 시나리오를 자동 실행한다.
# 결과: demo/raw.webm (에뮬레이터 호스트 녹화), demo/captions.tsv (녹화 시작 기준 초, 자막)
# 전제: Pleos Connect Emulator 부팅 완료, 앱 설치됨. 영상 합성은 tools/demo-render.py
# 차량 값은 Virtual Console로 넣는 가상 값이다. 에뮬레이터에 마이크 입력이 없으므로
# 발화는 예시 발화 버튼으로 STT 결과와 같은 해석 경로를 실행한다.
set -uo pipefail

ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=$ROOT/demo
mkdir -p "$OUT"
CAPTIONS=$OUT/captions.tsv
: > "$CAPTIONS"
BASE=http://127.0.0.1:8088
CLIENT="chagok-demo-$$"
UI_XML=$OUT/.ui.xml
PKG=io.chagok.app

"$ADB" forward tcp:8088 tcp:8088 > /dev/null

# Virtual Console은 브라우저 heartbeat가 있어야 활성화된다
(while true; do curl -s -m 2 -X POST "$BASE/browser/session?client=$CLIENT" -o /dev/null || true; sleep 1; done) &
HEARTBEAT=$!
RECORDING=
cleanup() {
    kill "$HEARTBEAT" 2> /dev/null || true
    [ -n "$RECORDING" ] && "$ADB" emu screenrecord stop > /dev/null 2>&1
    rm -f "$UI_XML"
}
trap cleanup EXIT

vc() {
    local code=000
    for _ in 1 2 3; do
        code=$(curl -s -m 10 -X PUT "$BASE/vehicle/$1" -o /dev/null -w '%{http_code}' || true)
        [ "$code" = "200" ] && break
        sleep 1
    done
    echo "PUT /vehicle/$1 -> $code"
}

# 화면에서 텍스트로 노드를 찾는다. 첫 인자가 prefix: 로 시작하면 앞부분 일치로 찾는다
find_point() {
    "$ADB" shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    "$ADB" pull /sdcard/ui.xml "$UI_XML" > /dev/null 2>&1
    python3 - "$1" "$UI_XML" << 'EOF'
import re, sys
want, path = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf8').read()
prefix = want.startswith('prefix:')
key = want[7:] if prefix else want
pattern = re.escape(key) + (r'[^"]*' if prefix else '')
for m in re.finditer(r'text="' + pattern + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s):
    x1, y1, x2, y2 = map(int, m.groups())
    # 화면 안에 충분히 보이는 노드만 누른다 (상단 상태바·하단 독 제외)
    cy = (y1 + y2) // 2
    if 90 < cy < 640:
        print(f"{(x1 + x2) // 2} {cy}")
        break
EOF
}

# 내용 영역 스크롤: up = 아래 내용 보기, down = 맨 위로
scroll() {
    if [ "$1" = up ]; then
        "$ADB" shell input swipe 1040 560 1040 220 500
    else
        "$ADB" shell input swipe 1040 220 1040 600 300
        "$ADB" shell input swipe 1040 220 1040 600 300
    fi
    sleep 0.6
}

tap() {
    local point=
    for _ in 1 2 3 4; do
        point=$(find_point "$1")
        [ -n "$point" ] && break
        scroll up
    done
    echo "tap $1 -> ${point:-NOT_FOUND}"
    if [ -z "$point" ]; then
        # 시나리오가 어긋나면 잘못된 영상이 남지 않도록 멈춘다
        echo "scenario aborted: '$1' not on screen" >&2
        exit 1
    fi
    # shellcheck disable=SC2086
    "$ADB" shell input tap $point
    sleep 0.8
}

# 부팅 직후에는 내비게이션 등 시스템 앱이 나중에 떠서 앱을 덮을 수 있다. 앱이 맨 앞에 올 때까지 다시 띄운다
ensure_app() {
    for _ in $(seq 1 10); do
        "$ADB" shell am start -n $PKG/.ui.MainActivity > /dev/null
        sleep 4
        [ -n "$(find_point "01  오늘의 관리")" ] && return 0
    done
    echo "app did not come to front" >&2
    exit 1
}

nav() { tap "$1"; scroll down; }

START=0
caption() {
    local now
    now=$(python3 -c "import time; print(f'{time.time() - $START:.2f}')")
    printf '%s\t%s\n' "$now" "$1" >> "$CAPTIONS"
    echo "[$now] $1"
}

wait_active() {
    # 부팅 직후에는 차량 서비스가 준비되지 않아 비활성으로 남는다
    for _ in $(seq 1 240); do
        curl -s -m 5 "$BASE/vehicle/state" | grep -q '"emulator_active":true' && return 0
        sleep 1
    done
    echo "virtual console did not become active" >&2
    exit 1
}

# ---------------------------------------------------------------- 준비 (녹화 전)
wait_active
vc "gear?gear=Park"
vc "odometer?traveled_distance_km=47200"
vc "ev-battery?field=soc&value=30"
"$ADB" shell am force-stop $PKG
"$ADB" shell pm clear --user current $PKG > /dev/null
"$ADB" shell pm grant --user current $PKG android.permission.RECORD_AUDIO
ensure_app
sleep 4
# 에뮬레이터 차량은 전기차다
tap "차량 설정"
tap "전기차"
tap "저장"
scroll down
# SoC 30%가 기준선이 되도록 정차 상태로 기다린다 (10초 안정 + 5초 주기 재입력)
sleep 20
ensure_app

# ---------------------------------------------------------------- 녹화
rm -f "$OUT/raw.webm"
"$ADB" emu screenrecord start --time-limit 180 --fps 30 --bit-rate 8M "$OUT/raw.webm" > /dev/null
RECORDING=1
START=$(python3 -c "import time; print(time.time())")
sleep 1

caption "주차된 차에서 차곡 실행 · Pleos Vehicle SDK로 누적 주행거리 47,200km 수신 (수신 시각 함께 표시)"
sleep 4

caption "관리 주기는 사용자가 정함 · 이 시연은 계산 설명용 예시값(10,000km · 12개월) 사용"
nav "05  관리 일정"
tap "시연용 예시값 넣기"
sleep 3

caption "정비 입력 · Pleos STT ON_DEVICE 한국어 (에뮬레이터에 마이크가 없어 예시 발화로 같은 해석 경로 실행)"
nav "02  정비 입력"
sleep 2
tap "타이어 위치 교환했어. 3만 원"
sleep 1
caption "“타이어 위치 교환했어. 3만 원” → 초안: 오늘 날짜 · 차량에서 받은 47,200km · 30,000원 · 확인할 항목"
scroll up
sleep 4

caption "확인하고 저장 → 다음 관리 계산: 57,200km 또는 1년 뒤 중 먼저 오는 조건"
tap "확인하고 저장"
scroll down
sleep 4

caption "충전 · Virtual Console로 SoC 30% → 80% 주입 (가상 값)"
nav "03  자동 기록 후보"
vc "ev-battery?field=soc&value=80"
sleep 3
caption "정차 중 값이 안정될 때까지 기다린 뒤 이전 관측과 비교"
sleep 12
caption "충전 후보 생성 · 확인 전에는 일지·비용에 넣지 않고, 충전량·금액을 %에서 추정하지 않음"
sleep 4

caption "“충전했어. 1만 2천 원” → 충전 후보와 연결된 초안"
nav "02  정비 입력"
tap "충전했어. 1만 2천 원"
scroll up
sleep 4
tap "확인하고 저장"
scroll down
sleep 2

caption "일지·비용 · 확정한 기록만 월 합계에 반영 (주행거리 출처 · 작성자 함께 표시)"
nav "04  일지·비용"
sleep 3
scroll up
sleep 2

caption "관리 일정 · 거리와 날짜 조건을 함께 보여 주고 먼저 오는 조건으로 도래 판단"
nav "05  관리 일정"
sleep 4

caption "관리 도감 · 내 일지 값으로 만든 정차 중 미션"
nav "06  관리 도감"
sleep 2
scroll up
sleep 2
caption "오답은 감점 없이 힌트를 주고 다시 시도"
tap "다음 정비까지 남은 거리"
sleep 3
tap "정비할 때까지의 누적 주행거리"
caption "정답 → '일지 첫걸음' 도장 · 이어서 '교환 주기 읽기' 미션"
sleep 3
scroll up
sleep 2
tap "prefix:57,200km 또는"
sleep 1
scroll down
caption "도장 2개 완성 · 도장은 학습 진행이며 차량 상태나 운전 실력 점수가 아님"
sleep 4

caption "오늘의 관리 · 다음 관리와 확인할 기록을 한 화면에"
nav "01  오늘의 관리"
sleep 4
caption "차곡 · 일지는 차곡차곡, 차 관리는 조금씩 익숙하게"
sleep 3

"$ADB" emu screenrecord stop > /dev/null
RECORDING=
sleep 2
echo "recorded $OUT/raw.webm"
