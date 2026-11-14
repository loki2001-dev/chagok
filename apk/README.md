# APK

| 항목 | 내용 |
|---|---|
| 파일 | `차곡차곡_차곡_release.apk` |
| 다운로드 | [GitHub Release v0.1.0](https://github.com/loki2001-dev/chagok/releases/tag/v0.1.0) |
| 크기 | 약 131MB |
| 빌드 | `./gradlew :app:assembleRelease` (release 빌드 타입, 버전 0.1.0) |
| 실행 환경 | Pleos Connect v2.1.0 에뮬레이터 (Android 14 / API 34, 1408 × 792, 160dpi) |

APK에 전국 도로 등급 팩(약 87MB)과 주행 장면 이미지가 들어 있어 GitHub의 저장소 파일 크기 제한(100MB)을 넘습니다. 그래서 이 폴더에는 APK를 직접 두지 않고 GitHub Release에 등록했습니다.

```bash
adb install -r -g 차곡차곡_차곡_release.apk
adb shell am start -n io.chagok.app/.companion.CompanionActivity
```

현재 release 빌드는 개발용(debug) 키로 서명되어 있고 난독화를 쓰지 않습니다.
