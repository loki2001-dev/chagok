package ai.chagok.core.share

import ai.chagok.core.detect.EnergyCandidate
import ai.chagok.core.diary.DiaryEntry
import ai.chagok.core.learning.Topic
import ai.chagok.core.schedule.ScheduleStatus
import java.time.Instant

/** RECEIVED 방금 받은 값 · STALE 오래된 값(마지막 수신 시각 표시) · NONE 미수신 · REVIEW 역행·차량 변경 의심으로 검토 대기 */
enum class OdometerState { RECEIVED, STALE, NONE, REVIEW }

data class OdometerView(val km: Double?, val receivedAt: Instant?, val state: OdometerState, val observedDuringReview: Double? = null)

/** 확인할 기록. 오늘 화면에서 정비 완료 처리를 하지 않는다: 알림 확인만으로 완료로 바꾸지 않는다. */
sealed class CheckItem {
    data class PendingDraft(val entry: DiaryEntry) : CheckItem()
    data class PendingCandidate(val candidate: EnergyCandidate) : CheckItem()
    data class OdometerReview(val observedKm: Double, val previousKm: Double?) : CheckItem()
}

/**
 * 정차 후 "오늘의 관리". 1~2초 안에 읽히도록 다음 관리 한 건과 확인할 기록을 먼저 둔다.
 * next: 가장 급한 관리 한 건(미루기·끄기한 계획 제외). schedule: 전체 일정(같은 항목은 한 카드).
 * familyUpdates: 이 구성원이 마지막으로 확인한 뒤 다른 가족이 확정한 기록(공용 일지의 변경 사항).
 * recommendedTopic: 상황에 맞는 학습 하나만. 강제하지 않는다.
 */
data class TodayBriefing(
    val vehicleId: String,
    val memberId: String,
    val odometer: OdometerView,
    val next: ScheduleStatus?,
    val schedule: List<ScheduleStatus>,
    val toCheck: List<CheckItem>,
    val familyUpdates: List<DiaryEntry>,
    val recommendedTopic: Topic?,
)
