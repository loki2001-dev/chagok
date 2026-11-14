# 변경 사항 올리기

한 커밋에는 한 가지 변경 목적을 담습니다. 기능과 그 기능을 검증하는 테스트는 함께 두고, 독립적인 수정이나 문서 변경은 별도 커밋으로 나눕니다. 의존성이 있는 변경은 바탕이 되는 코드부터 쌓습니다.

제목은 `접두어. 한국어 설명` 형식으로 작성합니다.

| 접두어 | 용도 | 예시 |
|---|---|---|
| `ADD.` | 새 기능·도구·테스트 | `ADD. 운행 기록에 정차 구간 보관` |
| `FIXED.` | 오류·회귀 수정 | `FIXED. 미확정 주유비가 월 합계에 포함되는 오류 수정` |
| `UPDATE.` | 기존 기능·설명 갱신 | `UPDATE. 주유비 확인 시나리오로 실행 안내 갱신` |
| `MERGE.` | 작업 브랜치 병합 | `MERGE. 자동 차량일지와 주행 동반 화면 병합` |

공개 브랜치에서는 필요한 파일만 `git add -- <경로>`로 올리고 `git diff --cached`로 확인합니다. 로컬 SDK·AVD·개인 설정·서명 키·원시 로그는 제외합니다. 앱에 쓰는 그림과 글꼴은 필요한 라이선스 파일과 함께 올립니다.

병합 전에 작업 브랜치에서 `git log --reverse --oneline origin/main..HEAD`로 순서를 확인하고 `git diff --check origin/main...HEAD`와 변경 범위에 해당하는 빌드·테스트를 실행합니다.

## 작업 이력을 보존해서 올리기

로컬에서 병합할 때는 먼저 `git fetch origin`을 실행하고, `main`에서 `git merge --ff-only origin/main`으로 기준을 맞춥니다. 이어서 아래와 같이 작업 브랜치를 `--no-ff`로 병합합니다. 예시의 브랜치명과 제목은 작업에 맞게 바꿉니다.

```sh
git merge --no-ff feature/auto-diary-companion -m "MERGE. 자동 차량일지와 주행 동반 화면 병합"
git log --graph --oneline origin/main..main
git push origin main
```

보호된 브랜치에서 PR이 필요하면 작업 브랜치를 푸시하고 GitHub에서 **Create a merge commit**을 선택합니다. 두 방식 모두 기능별 커밋과 병합 지점을 보존합니다.
