# 정비 저장 후 연출 연결

정차 상태에서 정비 기록을 확정하면 저장된 항목에 맞춰 정비소 입차, 작업, 출차 장면이 한 번 재생된다. 정비소 방문 질문에서 연 초안과 대시보드에서 직접 작성한 초안은 같은 저장 경로를 사용한다.

| 저장된 정비 내용 | BayJob | 선택 데이터 |
|---|---|---|
| 엔진오일 교환 | OIL | oilBrand, oilLitres |
| 드라이브 벨트 교체 | DRIVE_BELT | |
| 변속기 오일 교환 | TRANSMISSION_FLUID | |
| 브레이크 오일 교환 | BRAKE_FLUID | |
| 점화플러그·코일 교체 | IGNITION | |
| 에어컨 필터 교체 | CABIN_FILTER | |
| 타이어 교체 | TIRE | wheels에 교체한 위치 전달 |
| 배터리 교체 | BATTERY_REPLACE | 기존 12V 점검은 BATTERY |

제조사와 사용량은 값이 있는 경우에만 표시한다. 타이어 위치가 비어 있으면 특정 바퀴를 표시하지 않고 여분 타이어를 보여준다. 주행 제한·화면 일시정지 시 연출을 닫는다.

## 방문부터 대시보드까지 시연하기

[PLEOS 통합 시연 영상](workshop-visit.mp4)에서 정차 대기 구간을 줄여 출차 후 두 질문, 벨트 교체 장면과 차량 요약을 볼 수 있다.

PLEOS 에뮬레이터에 최신 smoke 앱과 Android 테스트 APK를 설치하고 다음 명령을 실행한다. 시연 앱에 시험용 업체 한 곳과 벨트 교체 주기 12개월을 준비한다.

```powershell
. .\tools\dev-env.ps1
python tools/smoke-device.py --workshop-visit
```

업체 근처를 주행할 때는 방문이 생성되지 않는다. 같은 위치에 P단으로 정차해 새 GPS 관측이 60초 누적돼도 질문은 보류한다. 차량이 업체에서 150m 밖으로 나간 뒤 동반 화면에서 “방금 정비하셨나요?”를 묻는다. “네”라고 답하면 “무엇을 정비하셨나요?”가 이어진다. 항목을 말하면 비용 없이도 저장되며, 함께 말한 금액은 일지에 반영된다. “아니요”는 해당 방문을 제외하고 “나중에”는 확인할 기록에 남긴다.

이 테스트는 실제 PLEOS 위치·속도·기어 신호와 에뮬레이터 GPS를 사용한다. 두 답변은 음성 인식의 최종 텍스트가 전달되는 함수에 “네”, “드라이브 벨트 교체 9만 5천 원”을 넣는다. 주행 중에는 기록만 저장하고, 다음 P단 정차 때 축소된 정비 애니메이션을 재생한다. 차량 요약에서 다음 교체 예정일을 확인하고 최근 일지 상세에서 업체명을 확인한다.

## 에뮬레이터에서 모션만 시연하기

PLEOS 에뮬레이터를 정차 상태로 두고 smoke APK와 smoke AndroidTest APK를 설치한다. 다음 테스트는 별도 smoke 앱의 시연 데이터를 초기화한다. 8개 정비 항목과 워셔액을 순서대로 재생한다. 제조사와 리터 수는 시연용 예시다.

```powershell
. .\tools\dev-env.ps1
$driver = (adb shell am get-current-user).Trim()
adb shell am instrument --user $driver -w -e workshopDemo true -e class 'io.chagok.app.ServiceBaySmokeTest#maintenanceMotionPresentation' io.chagok.app.smoke.test/androidx.test.runner.AndroidJUnitRunner
```

벨트·점화플러그/코일·에어컨 필터·배터리 교체는 정차 후 작업 부위를 확대해 분리부터 재장착까지 보여준다. 브레이크 패드와 배터리 점검은 각각 두께 게이지와 전압계 작업으로 표시한다.
