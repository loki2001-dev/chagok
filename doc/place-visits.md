# 정비소·주유소 방문 감지

## 사용 흐름

1. 차량 자동 기록을 켜고 P단으로 정차한다.
2. 더보기 → 정비소·주유소 방문 감지에서 **HTTPS 목록 주소를 저장**하거나 JSON 목록 파일을 가져온다.
3. 방문 감지를 켜고 `pleos.car.permission.FUSED_LOCATION` 권한을 허용한다. 기본값은 꺼짐이다.
4. 단일 업체 60m 이내에서 새 위치와 정차 신호가 60초 누적되면 방문 확인 후보를 만든다. 150m 밖으로 이동해야 같은 업체의 새 방문으로 구분한다.
5. 정비소는 체류 중 질문을 보류한다. 이탈 거리 150m를 넘은 뒤 동반 화면에서 “방금 정비하셨나요?”, “무엇을 정비하셨나요?”를 묻고 답한 항목을 저장한다. 주행 중에는 음성으로 답하고, 정차 시 터치 응답도 가능하다. 주유소 방문 질문은 정차 시 표시한다. 오늘 질문 끄기 설정은 두 종류에 공통 적용된다.
6. 미뤄 둔 방문은 정차 시 확인 화면에서 업체와 항목을 선택하고 기존 일지 초안을 검토해 저장한다. 금액을 몰라도 저장할 수 있다. 이용하지 않았다면 제외한다.

GPS 근접은 업체 이용 증거가 아니다. 여러 업체가 겹치거나 위치가 30초 이상 오래됐거나 미래 시각이거나 P단·속도·UX 신호가 불명확하면 새 방문을 확정하지 않는다. SDK `confidence`를 미터 단위 정확도로 해석하지 않는다. 위치 궤적을 저장하거나 서버에 좌표를 전송하지 않는다. 도착 위치에 해당하는 업체 정보와 방문 시각만 후보에 보관한다.

앱/시동 공백 120초 이상 뒤 같은 업체가 다시 관측되면 미완료 도착을 확인 후보로 복구한다. 공백을 관측 체류 시간에 더하지 않고 사용자에게 표시한다. 프로세스가 실행되지 않는 동안의 출입을 보장하는 시스템 지오펜스는 아니다.

## 목록 공급과 갱신

앱에는 운영 목록 주소, API 키, 실제 전국 목록을 내장하지 않았다. **배포자가 아래 형식의 실제 목록을 HTTPS에 게시하고 주소를 설정해야 최초 다운로드가 가능하다.** 파일 가져오기는 네트워크 없이 사용할 수 있다. 파일을 가져오면 기존 자동 다운로드 주소를 해제해 다음 갱신에 덮어쓰이지 않게 한다.

앱 사용 중 감지 서비스가 실행되는 동안 하루 한 번 성공한 확인 이후 다시 확인한다. 네트워크 실패는 이후 서비스의 시간별 검사에서 재시도하고, 사용자가 즉시 확인할 수도 있다. 서버가 `ETag`를 제공하면 `If-None-Match`를 보내 304 응답에서 본문 다운로드를 생략한다. ETag가 없는 서버는 본문을 받고 버전을 비교한다. 리디렉션은 따라가지 않으므로 최종 HTTPS 주소를 입력한다.

새 목록을 전부 다운로드·검증한 뒤 SQLite 트랜잭션으로 교체한다. 비정상 응답, 깨진 JSON, 빈 목록, 중복 ID, 잘못된 좌표, 구버전, 같은 버전의 다른 내용은 거부한다. 다운로드 중 주소 변경 또는 파일 교체가 일어나면 이전 다운로드를 적용하지 않는다. 기존 방문 후보는 당시 업체 정보 사본을 유지한다.

### JSON 형식

아래 좌표·이름은 **형식 설명용 합성 예시이며 실제 업체가 아니다.**

```json
{
  "schemaVersion": 1,
  "version": 1,
  "publishedAt": "2026-11-15T00:00:00Z",
  "places": [
    {"id":"repair:stable-id","name":"시험용 정비소","kind":"REPAIR",
     "latitude":37.5,"longitude":127.0,"address":"합성 주소","source":"시험 데이터"}
  ]
}
```

`kind`는 `REPAIR` 또는 `FUEL`. `version`은 내용이 바뀔 때마다 증가하는 양의 정수다. 업체 ID는 좌표 수정에도 유지해야 한다. 상한은 100,000곳/20MiB다. `publishedAt`은 배포 시각이며 모든 업체의 최신 상태를 보장하는 기준일이 아니다. 운영자는 원본 제공기관·수집일·데이터 기준일을 별도로 관리해야 한다.

### 공공 CSV 변환

원본은 [전국자동차정비업체표준데이터](https://www.data.go.kr/data/15028204/standard.do), [전국주유소표준데이터](https://www.data.go.kr/data/15129441/standard.do)의 CSV를 사용한다. 제공처 갱신과 앱의 다운로드 확인은 다른 주기이며, 실시간 개폐업 알림을 의미하지 않는다. 원본의 이용 조건과 최신성을 확인한 후 운영 목록으로 게시한다.

```sh
python3 tools/build_place_catalogue.py --repair repair.csv --fuel fuel.csv --version 1 --output places.json
# 오래된 한글 CSV 인코딩이면 --encoding cp949
```

변환기는 이름·주소·좌표를 정규화하고 영업하지 않는 것으로 표시된 정비소를 제외한다. 알 수 없는 영업상태는 보수적으로 제외한다. 좌표 누락·중복 ID 등은 오류로 중단하므로 배포 전에 원본을 정리한다. 이름/주소 변경은 현재 변환기에서 새 ID가 되므로 제공기관 고유 ID를 확보하면 그 ID로 전환하는 것이 좋다. 변환 도구는 공공데이터를 직접 다운로드하거나 원본 변경을 감시하는 서버 작업까지 운영하지 않는다.

## 검증

```sh
./gradlew :app:testSmokeUnitTest :app:assembleDebug :app:assembleSmokeAndroidTest :app:lintDebug
python3 -m unittest discover -s tools -p 'test_*.py'
```

단위 테스트는 위치 신선도·정차·중복 후보·공백·작성자 분리·확인/취소 및 ETag·원자적 목록 적용 규칙을 검사한다. Android 테스트 `PlaceVisitDeviceTest#catalogueRollbackAndCrossConnectionInvalidation`은 실제 SQLite 롤백과 연결 간 캐시 무효화를 검사한다.

실제 Pleos 위치 probe는 외부에서 위치를 주입한 뒤 명시적으로 실행한다. `.smoke` 앱에만 설치하며 실제 사용자 앱 DB를 변경하지 않는다.

```sh
adb shell pm grant io.chagok.app.smoke pleos.car.permission.FUSED_LOCATION
adb emu geo fix 127.0 37.5
adb shell am instrument -w -e class io.chagok.app.PlaceVisitDeviceTest#fusedLocationMatchesInjectedPosition \
  -e placeProbe true -e latitude 37.5 -e longitude 127.0 \
  io.chagok.app.smoke.test/androidx.test.runner.AndroidJUnitRunner
```

이 probe는 실제 SDK에서 받은 위치·시각이 주입값에 맞는지와 그 좌표에 놓은 **합성 업체**의 근접 후보 생성을 검사한다. 전국 실데이터의 정확도, 실제 정비소 방문, 실차 백그라운드 지속 동작을 검증한 결과와 구분해야 한다. 결과는 smoke 앱 내부 `place-sdk-probe.json`에 저장한다. 위치 서비스가 없는 이미지에서는 성공으로 처리하지 않는다.

Pleos 권한과 백그라운드 정책은 [공식 FusedLocation 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)를 기준으로 한다. Google Fused Location의 보장을 Pleos SDK에 그대로 적용하지 않는다.

### 2026-11-15 검증 결과

- 기능 변경만 분리한 사본에서 JVM 단위 시험, CSV 변환 시험, debug/smoke APK 및 Android 테스트 APK 빌드, `lintDebug`를 통과했다.
- 공식 Pleos Connect v2.1.0 ARM64 이미지에서 `PlaceVisitDeviceTest` 2개를 실행해 통과했다. FusedLocation 서비스 지원과 런타임 권한을 확인했고, 외부에서 주입한 `37.5, 127.0` 좌표를 SDK가 실제로 반환했다.
- 해당 좌표의 합성 업체에 대한 WATCHING 후보 생성과 실제 SQLite의 롤백·연결 간 캐시 갱신을 확인했다. 실제 업체 목록 정확도, 60초 실시간 체류 후 자동 질문부터 일지 저장까지의 전체 UI, 실차의 장시간 백그라운드 동작은 이 기기 테스트의 검증 범위에 포함되지 않는다.
