# 도로 감지와 주행 통계

차곡이 설치된 차량의 도로 등급별 주행거리와 주행 시간을 로컬 SQLite에 누적한다. 소비 함수는 날짜만 지정한다. 소모품 교체 주기 계산이나 제조사 매뉴얼 적용은 하지 않는다.

```kotlin
val usage = diary.roadUsage(
    LocalDate.of(2026, 11, 1),
    LocalDate.of(2026, 11, 30), // 양 끝 날짜 포함, Asia/Seoul
)
val highwayKm = usage.buckets.getValue(RoadBucket.MOTORWAY).distanceKm
val highwayMillis = usage.buckets.getValue(RoadBucket.MOTORWAY).movingMillis
val unknownKm = usage.buckets.getValue(RoadBucket.UNKNOWN).distanceKm
val knownKm = usage.classifiedDistanceKm
val totalKm = usage.totalDistanceKm
val gaps = usage.observationGapMillis // 주행했다고 추정하지 않는 미관측 시간
val missingDistance = usage.distanceMissingMillis // 거리 신호 없이 관측한 주행 시간
val rejected = usage.rejectedDistanceSamples // 역행·비현실적 거리 변화
```

`DiaryStore`를 직접 받는 함수에서는 `RoadUsageQuery.summary(store, from, through)`를 사용할 수 있다. 차량 선택 인자는 없다. 저장소의 기존 차량 식별자는 내부 자료 구분에만 사용한다. 시간 단위는 정수 밀리초, 거리는 실수 km다. 관측하지 못한 시간을 주행 시간으로 복원하지 않는다. 누적값은 해당 기간에 관측한 값이며 실제 총 운행거리·시간을 보장하지 않는다.

## 도로 등급과 시내·시골의 차이

| 버킷 | OSM highway 값 | 의미 |
|---|---|---|
| MOTORWAY | motorway | 고속도로 지도 추정 |
| TRUNK | trunk | 간선도로 지도 추정; 한국 법정 자동차전용도로와 동일하지 않음 |
| ROAD | primary, secondary, tertiary, unclassified | 일반도로 |
| LOCAL | residential, living_street | 생활도로 |
| SERVICE | service | 진입·구내도로 |
| TRACK | track | 임도·농로 |
| LINK | 위 주요 등급의 `_link` | 연결도로 |
| UNKNOWN | 위치·지도·등급 근거 부족 | 미분류 |

이 분류는 법적 도로 등급, 주행 허가, 포장 상태, 도시/농촌 환경을 확정하지 않는다. `LOCAL=시내`, `TRACK=시골`로 바꾸면 안 된다. 도시/농촌 근거 데이터는 아직 없으며 `environmentUnknownDistanceKm`은 전체 관측 거리다. 다른 함수에서도 미분류를 숨기거나 자동으로 일반도로에 합치지 않는다.

## 감지와 집계 기준

- 정차 상태의 `도로·정비소·주유소 감지 설정`에서 도로 감지를 켠다. 기본 꺼짐, Pleos 위치 권한 필요. 방문 감지와 독립적으로 켜고 끌 수 있다.
- 위치는 차 밖으로 전송하지 않는다. GPS 좌표/이동 궤적은 DB에 저장하지 않고 일 단위 합계와 마지막 누적 주행거리·시각만 보관한다.
- 자동 기록이 켜져 있으면 통계를 누적한다. 도로 감지가 꺼져 있거나 위치 권한이 없으면 거리·시간은 미분류다. 자동 기록 자체가 꺼져 있으면 수집하지 않는다.
- 새 위치(10초 이내)를 약 1초마다 확인한다. 19m 이내 도로만 후보로 삼고, 서로 다른 등급이 37m 이내이면서 최단거리 차이가 17m 이하면 미분류다. 지도 1m 단순화 오차를 고려한 보수적 임계값이며 GPS 오차 보증은 아니다.
- 거리·속도·시각을 함께 캡처하고 해당 위치의 비동기 도로 판정이 완료된 뒤 집계한다. 느린 조회 중 후속 관측은 미분류 처리하며 늦게 도착한 이전 관측은 최신 체크포인트를 덮어쓰지 않는다.
- 동일 등급의 서로 다른 위치 관측이 3초 이상 이어져야 등급을 표시한다. 평행·입체교차의 다른 등급은 보류하며 같은 등급끼리는 특정 도로의 식별을 주장하지 않는다. 지도 누락·GPS 편향으로 오분류할 가능성은 남는다.
- 거리에는 **차량 누적 주행거리 차이**만 쓴다. 차량 SDK의 오도미터 갱신 사이에 등급이 바뀌거나 관측이 끊겼다면 전체 차이를 미분류에 더한다. GPS 직선거리나 속도 적분으로 빈 값을 채우지 않는다.
- 주행 시간은 양 끝 속도가 0.5m/s 초과이고 차량 연결이 확인된 연속 5초 이하 구간만 누적한다. 관측 공백 시간은 따로 반환하며 실제 주행 시간으로 간주하지 않는다.
- 거리 변화의 상한은 관측 간 초 × 0.08km + 1km이다. 역행·이상값은 제외하고 마지막 정상 기준점을 유지한다. 오류 관측 시각은 별도로 저장하여 캐시 관측·재시작으로 같은 오류를 다시 세지 않는다. 정상 복귀까지의 거리는 미분류다. 지속적인 거리계 리셋을 자동 수락하지 않는다. 제외한 거리량은 알 수 없으므로 횟수만 반환한다.
- 집계와 체크포인트를 같은 SQLite 트랜잭션에 기록해 같은 시각의 중복 샘플·재시작 재생을 방지한다. 재시작 사이의 타당한 거리 차이는 미분류다.
- 날짜를 넘는 거리/시간 구간은 종료 날짜에 미분류로 넣는다. 정확한 자정 위치를 역산하지 않는다.

## 전국 지도 자료

- 원본: [Geofabrik 대한민국](https://download.geofabrik.de/asia/south-korea.html), `2026-11-13T21:22:20Z` 스냅샷, 약 278MiB.
- [앱용 도로 팩](../../app/src/main/assets/roads/korea-v1.zip): 90,687,215 bytes (86.49MiB), 26,201개 타일, 9,601,037개 선분. 전국 추출본의 데이터이지 모든 실제 도로의 누락 없는 수록을 보증하지 않는다.
- Douglas-Peucker 1m 허용오차로 단순화한 자동차 도로 선분만 포함. 지역 격자 0.02도, 겹치는 모든 타일에 선분 등록. 이름/개인 기여자 정보는 포함하지 않는다. 제한·사유도로도 오분류 방지 후보로 남긴다.
- 작업 스레드에서 현재 위치 주변 1~4개 타일만 읽는다. 최대 16개 타일 LRU, 메인 스레드에서 전국 도형을 파싱하지 않는다.
- 현재는 앱에 초기 자료를 포함하고 앱 업데이트로 교체한다. 최초 감지 때 캐시에 복사하므로 앱 설치 크기 외 약 87MiB의 추가 공간이 필요하다. 별도 지도 서버나 주기적 다운로드는 이번 구현에 없다.
- 팩 SHA-256, 원본 SHA-256, 출처 시각은 `app/src/main/assets/roads/korea-v1.{sha256,json}`에 있다. 원본 MD5도 Geofabrik 제공 값과 대조했다.
- 데이터 © OpenStreetMap contributors, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/). 변환된 데이터도 ODbL로 공개한다. 앱 코드 라이선스와 별개이며 전문은 자산 폴더에 동봉한다. [출처/저작권 안내](https://www.openstreetmap.org/copyright).

## 재생성

Python 3.11 이상, `osmium==4.3.2` 필요. 원본 PBF는 Git에 넣지 않는다.

```sh
python tools/roads/build_pack.py south-korea-latest.osm.pbf app/src/main/assets/roads/korea-v1.zip
python tools/roads/test_build_pack.py
```

스크립트는 ZIP, JSON 메타데이터, SHA-256 파일을 함께 생성한다. 갱신 자료의 크기·라이선스·원본 체크섬을 확인하고 테스트한 뒤 배포한다.

## 검증

단위 테스트는 교차/평행도로, 위치 신선도, 캐시 관측, 공간 타일 경계, 거리 변화, 관측 공백, 재시작, 날짜 경계, 프로필 내부 분리와 직렬화를 다룬다. SQLite 기기 시험은 재오픈 후 합계와 중복 방지, 기존 v5 일지의 최신 스키마 업그레이드 보존을 검증한다. 최신 main 통합 후 앱 단위 테스트 167개, core 테스트 78개, 변환 도구 테스트 4개, 최종 기기 테스트 5개가 통과했다.

실제 Pleos Connect 2.1.0 / API 34 에뮬레이터의 FusedLocation에 좌표를 주입해 배포용 전국 팩으로 검사했다:

| 지점 | 좌표 (위도, 경도) | 기대 결과 | 최초 조회 |
|---|---|---|---|
| 판교 경부고속도로 | 37.3823245, 127.1033145 | MOTORWAY | 68ms |
| 양재 동산로2길 | 37.4689616, 127.0422078 | LOCAL | 48ms |
| 양평 연수로 | 37.50161675, 127.57350395 | ROAD | 27ms |
| 판교 도로 중첩 지점 | 37.39438915, 127.1011471 | UNKNOWN | 101ms |

첫 중첩 지점 시험에서 MOTORWAY 기대가 실패했다. 실제 지도에는 일반도로가 약 1.2m 거리에 겹쳐 있으므로 UNKNOWN이 올바른 보류이며, 단순 최단거리 방식의 이전 실험과 구분해 검사한다. 임계값을 낮춰 억지로 통과시키지 않았다.

이는 정적인 에뮬레이터 주입 검증이다. 실차 주행 정확도, 전체 전국 지점, 고가/터널 층위, 시내/시골 분류 및 실제 차량 주행거리 연동 시나리오를 검증했다는 뜻은 아니다.

```sh
./gradlew -ProadProbe=true :app:testSmokeUnitTest :app:assembleSmoke :app:assembleSmokeAndroidTest :app:lintDebug
# 별도 io.chagok.app.roadprobe 패키지. 기본 .smoke 작업과 충돌하지 않는다.
adb install -r --user 10 app/build/outputs/apk/smoke/app-smoke.apk
adb install -r --user 10 app/build/outputs/apk/androidTest/smoke/app-smoke-androidTest.apk
adb shell pm grant --user 10 io.chagok.app.roadprobe pleos.car.permission.FUSED_LOCATION
adb emu geo fix 127.1033145 37.3823245
adb shell am instrument --user 10 -w -e class io.chagok.app.RoadPackDeviceTest \
  -e roadProbe true -e latitude 37.3823245 -e longitude 127.1033145 -e expected MOTORWAY \
  io.chagok.app.roadprobe.test/androidx.test.runner.AndroidJUnitRunner
```

리뷰 회귀 시험에는 거리계 역행·양의 급증 후 정상 복귀, 오류 관측의 재시작·캐시 중복, v6 체크포인트 호환, 작업 큐와 메인 큐를 지연시킨 도로 전환·미분류·기능 해제·만료·종료 시나리오가 포함된다. Android 기기 시험도 실제 RoadDetector 작업 스레드/Handler와 RoadUsageRecorder를 연결해 새 위치 판정 전에 거리 합계가 쓰이지 않는지 확인한다. 이 시험의 위치·거리 입력은 합성 값이다.
