#!/usr/bin/env bash
# Pleos Connect Virtual Console로 차곡 시연 값을 넣는다 (가상 값 주입: 실제 차량 수신과 구분해서 기록한다).
# 사용:
#   tools/vc.sh odo <km>        누적 주행거리 (PERF_ODOMETER)
#   tools/vc.sh soc <percent>   EV 배터리 SoC (EV_BATTERY_SOC)
#   tools/vc.sh gear <Park|Drive>
#   tools/vc.sh state           현재 odometer·ev_battery 값
# 전제: 에뮬레이터 부팅 완료. Virtual Console은 브라우저 heartbeat가 있어야 값을 반영하므로 이 스크립트가 함께 보낸다.
set -euo pipefail

ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
BASE=http://127.0.0.1:8088
CLIENT="chagok-$$"

"$ADB" forward tcp:8088 tcp:8088 > /dev/null

heartbeat() {
    while true; do
        curl -s -m 2 -X POST "$BASE/browser/session?client=$CLIENT" -o /dev/null || true
        sleep 1
    done
}
heartbeat &
HEARTBEAT_PID=$!
trap 'kill $HEARTBEAT_PID 2> /dev/null || true' EXIT

wait_active() {
    local i
    for i in $(seq 1 15); do
        if curl -s -m 5 "$BASE/vehicle/state" | grep -q '"emulator_active":true'; then
            return 0
        fi
        sleep 1
    done
    echo "virtual console did not become active" >&2
    exit 1
}

put() {
    local code
    code=$(curl -s -m 10 -X PUT "$BASE/vehicle/$1" -o /dev/null -w '%{http_code}')
    echo "PUT /vehicle/$1 -> $code"
    [ "$code" = "200" ] || { echo "virtual console rejected request" >&2; exit 1; }
    # 값이 차량 서비스까지 전달될 시간을 둔다
    sleep "${HOLD:-2}"
}

wait_active
case "${1:-state}" in
    odo) put "odometer?traveled_distance_km=${2:?km}" ;;
    soc) put "ev-battery?field=soc&value=${2:?percent}" ;;
    gear) put "gear?gear=${2:?Park|Drive}" ;;
    state)
        curl -s -m 5 "$BASE/vehicle/state" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print("odometer=%skm soc=%s%% gear=%s" % (d["odometer"]["traveled_distance_km"], d["ev_battery"]["state_of_charge"], d["gear"].get("actual")))'
        ;;
    *) echo "usage: $0 odo <km> | soc <percent> | gear <Park|Drive> | state" >&2; exit 2 ;;
esac
