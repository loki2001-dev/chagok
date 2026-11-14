# core · 차곡 일지 규칙 엔진

이 폴더는 [blackmarine97/chagok](https://github.com/blackmarine97/chagok)의 `chagok/core`를 복사한 것이다.
기준: `869d0c9` (앱 통합용 변경 포함: 항목 5종, 기타 정비 대체, 소수 금액, 날짜 종류, 기준 지정 일정 계산, JVM 17).

- 순수 Kotlin/JVM 모듈. Android·Pleos SDK에 의존하지 않는다.
- 앱(`:app`)은 `io.chagok.app.domain`의 공개 API를 그대로 유지하고, 내부에서 이 모듈을 호출한다.
  - 발화 해석: `domain/UtteranceParser.kt` → `ai.chagok.core.voice.UtteranceParser`
  - 관리 일정 계산: `domain/Schedule.kt` → `ai.chagok.core.schedule.ScheduleCalculator`
- 주유·충전 감지와 차계부는 아직 앱 구현을 쓴다.

JVM 테스트: `.\gradlew.bat :core:test`
