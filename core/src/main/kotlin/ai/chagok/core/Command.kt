package ai.chagok.core

import ai.chagok.core.detect.EnergyCandidate
import ai.chagok.core.diary.DiaryEntry
import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.diary.Wheel
import ai.chagok.core.learning.Lesson
import ai.chagok.core.learning.LearningProgress
import ai.chagok.core.learning.Topic
import ai.chagok.core.schedule.PlanNotify
import ai.chagok.core.schedule.ScheduleStatus
import ai.chagok.core.share.ExportReport
import ai.chagok.core.share.ExportSpec
import ai.chagok.core.share.TodayBriefing
import ai.chagok.core.voice.VoiceFailure
import java.time.LocalDate

/**
 * 사용자 명령. 음성(STT 어댑터)과 화면이 같은 명령을 보낸다.
 * 일지 확인·일정 편집·비용·학습은 정차 전용이라 주행 제한 중에는 Engine이 거절한다(음성이라고 허용을 가정하지 않는다).
 */
sealed class Command {
    // ── 음성 세션. Pleos SpeechToText(ON_DEVICE, ko-KR): request() → onUpdated(stt, completed) → onEndedRecognition / onError ──
    data class VoiceStarted(val sessionId: String, val vehicleId: String, val memberId: String) : Command()
    data class VoicePartial(val sessionId: String, val text: String) : Command()
    /** completed=true로 온 최종 텍스트. 저장이 아니라 초안을 만든다. */
    data class VoiceFinal(val sessionId: String, val text: String) : Command()
    /** 인식이 끝났는데 최종 결과가 없다(무음 등). 중간 결과가 있으면 그것으로 초안을 만들고, 없으면 "말소리 없음". */
    data class VoiceEnded(val sessionId: String) : Command()
    data class VoiceFailed(val sessionId: String, val failure: VoiceFailure) : Command()
    /** 사용자가 듣기를 취소. SDK에 cancel이 없어 앱이 stop() 후 결과를 버리고 이 명령을 보낸다. */
    data class VoiceCancelled(val sessionId: String) : Command()

    // ── 일지 ──
    data class ManualDraft(
        val vehicleId: String, val memberId: String, val type: EntryType,
        val item: MaintenanceItem? = null, val wheels: Set<Wheel> = emptySet(),
        val performedOn: LocalDate? = null, val cost: Long? = null, val note: String? = null,
    ) : Command()
    data class EditEntry(val entryId: String, val memberId: String, val edit: EntryEdit) : Command()
    /** acceptApproximateDate: "지난주"처럼 추정한 날짜를 그대로 확정하겠다는 사용자의 명시적 확인. */
    data class ConfirmEntry(val entryId: String, val memberId: String, val acceptApproximateDate: Boolean = false, val allowDuplicate: Boolean = false) : Command()
    data class CancelEntry(val entryId: String, val memberId: String) : Command()

    // ── 관리 일정 ──
    data class SetPlan(val vehicleId: String, val item: MaintenanceItem, val intervalKm: Double?, val intervalMonths: Int?, val source: String) : Command()
    data class SnoozePlan(val vehicleId: String, val item: MaintenanceItem, val until: LocalDate) : Command()
    data class SetPlanNotify(val vehicleId: String, val item: MaintenanceItem, val notify: PlanNotify) : Command()
    data class QuerySchedule(val vehicleId: String) : Command()

    // ── 주유·충전 후보 ──
    data class ConfirmCandidate(val candidateId: String, val memberId: String, val cost: Long? = null) : Command()
    data class DismissCandidate(val candidateId: String, val memberId: String) : Command()

    // ── 오늘의 관리 · 가족 공유 ──
    data class OpenToday(val vehicleId: String, val memberId: String) : Command()
    data class MarkFeedSeen(val memberId: String) : Command()
    data class ResolveOdometerReview(val vehicleId: String, val accept: Boolean) : Command()
    data class Export(val spec: ExportSpec) : Command()

    // ── 관리 도감 ──
    data class StartLesson(val memberId: String, val vehicleId: String, val topic: Topic) : Command()
    data class AnswerQuiz(val memberId: String, val topic: Topic, val questionId: String, val choice: Int) : Command()

    /** 주행 제한 중에도 받는 명령: 음성 세션 정리(실패·취소·중간 결과)뿐이다. */
    val parkedOnly: Boolean get() = this !is VoiceFailed && this !is VoiceCancelled && this !is VoicePartial
}

/** 일지 수정. null인 필드는 그대로 둔다. 금액을 지우려면 clearCost(미입력으로 되돌림). */
data class EntryEdit(
    val cost: Long? = null,
    val clearCost: Boolean = false,
    val performedOn: LocalDate? = null,
    val odometerKm: Double? = null,
    val item: MaintenanceItem? = null,
    val wheels: Set<Wheel>? = null,
    val note: String? = null,
)

/** 초안 확인 화면에서 물어볼 것. prompt는 화면·TTS 기본 문구(짧게, 3줄 이내). */
enum class DraftQuestion(val prompt: String) {
    CONFIRM_DATE("날짜가 대략이에요. 실제로 관리한 날을 골라 주세요."),
    DATE_ASSUMED_TODAY("날짜를 말하지 않아 오늘로 두었어요. 오늘 한 관리가 맞나요?"),
    ASK_PAST_ODOMETER("지난 관리예요. 그때 주행거리를 알려 주세요. 지금 km는 참고값이에요."),
    ODOMETER_UNAVAILABLE("주행거리를 받지 못했어요. 직접 입력하거나 비워 두면 거리 조건은 계산을 미뤄요."),
    ASK_WHEELS("교체한 바퀴 위치를 골라 주세요."),
    ASK_COST("비용을 말하지 않았어요. 비워 두면 0원이 아니라 미입력으로 남아요."),
    CONFIRM_ROTATION("새 타이어 교체가 아니라 위치 교환이 맞나요?"),
}

/** 확인 화면에 함께 보여 줄 안내. 질문이 아니라 사실을 알린다. */
enum class DraftNotice(val text: String) {
    LINKED_TO_CANDIDATE("자동 기록 후보와 연결했어요. 맞는지 확인해 주세요."),
    NO_CANDIDATE("연결할 자동 기록 후보가 없어요. 수동 기록으로 저장돼요."),
    AMOUNT_NOT_ESTIMATED("주유량·충전량과 비용은 잔량(%)에서 추정하지 않아요."),
    REFILL_NOT_WARNING_RESET("보충 기록은 차량 경고가 꺼졌다는 뜻이 아니에요."),
    MEMO_NOT_DIAGNOSIS("증상 메모는 고장 진단이 아니에요."),
}

enum class RejectReason {
    DRIVING_RESTRICTED, NOT_FOUND, INVALID_STATE, DUPLICATE, NOT_AVAILABLE_FOR_POWERTRAIN,
    DATE_NEEDS_CONFIRMATION, INVALID_VALUE, LESSON_NOT_AVAILABLE,
}

/** 화면·TTS로 나가는 것. 모두 손실되면 안 되는 일회성 사건이다. */
sealed class EngineEvent {
    data class DraftReady(val entry: DiaryEntry, val questions: Set<DraftQuestion>, val linkedCandidate: EnergyCandidate?, val notices: Set<DraftNotice> = emptySet()) : EngineEvent()
    data class EntryConfirmed(val entry: DiaryEntry) : EngineEvent()
    data class EntryUpdated(val entry: DiaryEntry) : EngineEvent()
    data class EntryCancelled(val entry: DiaryEntry) : EngineEvent()
    data class ScheduleChanged(val vehicleId: String, val statuses: List<ScheduleStatus>) : EngineEvent()
    /** "다음 관리는 언제야?"에 대한 답. 확인된 설정 일정만 말한다. */
    data class ScheduleAnswer(val vehicleId: String, val statuses: List<ScheduleStatus>) : EngineEvent()
    data class CandidateCreated(val candidate: EnergyCandidate) : EngineEvent()
    data class CandidateUpdated(val candidate: EnergyCandidate) : EngineEvent()
    data class CandidateResolved(val candidate: EnergyCandidate) : EngineEvent()
    data class OdometerReviewNeeded(val vehicleId: String, val observedKm: Double, val previousKm: Double) : EngineEvent()
    /** 확정 기록끼리 주행거리 앞뒤가 맞지 않는다. 저장은 됐고, 사용자가 값을 다시 확인하도록 알린다. */
    data class OdometerInconsistent(val entryId: String, val conflictingEntryId: String) : EngineEvent()
    data class TodayReady(val briefing: TodayBriefing) : EngineEvent()
    data class LearningSuggested(val memberId: String, val topic: Topic) : EngineEvent()
    data class LessonReady(val memberId: String, val lesson: Lesson) : EngineEvent()
    /** 오답이면 힌트를 주고 다시 풀게 한다. 감점 없음. */
    data class QuizFeedback(val memberId: String, val topic: Topic, val questionId: String, val correct: Boolean, val hint: String?, val lessonComplete: Boolean) : EngineEvent()
    data class StampAwarded(val progress: LearningProgress) : EngineEvent()
    data class LearningBasisChanged(val progress: LearningProgress) : EngineEvent()
    data class ReportReady(val report: ExportReport) : EngineEvent()
    data class Clarification(val sessionId: String?, val question: String) : EngineEvent()
    data class Rejected(val command: Command, val reason: RejectReason) : EngineEvent()
}
