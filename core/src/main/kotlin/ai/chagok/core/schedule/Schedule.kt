package ai.chagok.core.schedule

import ai.chagok.core.diary.DiaryEntry
import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.diary.PlanKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class PlanNotify { ON, SNOOZED, OFF }

/**
 * 관리 계획. 주기는 사용자가 매뉴얼·정비 안내를 보고 정한 값이며 출처와 설정일을 보관한다.
 * SDK가 권장 주기를 준다고 가정하지 않는다.
 */
data class MaintenancePlan(
    val vehicleId: String,
    val item: MaintenanceItem,
    val intervalKm: Double? = null,
    val intervalMonths: Int? = null,
    val source: String,
    val setOn: LocalDate,
    val notify: PlanNotify = PlanNotify.ON,
    val snoozedUntil: LocalDate? = null,
) {
    val kind: PlanKind get() = item.planKind
    val isSet: Boolean get() = intervalKm != null || intervalMonths != null
    fun silencedOn(today: LocalDate): Boolean =
        notify == PlanNotify.OFF || (notify == PlanNotify.SNOOZED && snoozedUntil != null && today.isBefore(snoozedUntil))
}

/**
 * NOT_SET 주기 미설정 · NO_BASE_RECORD 기준 정비 기록 없음 · REVIEW 현재 km가 기준 km보다 작음(역행·차량 변경 의심)
 * UPCOMING 예정 · SOON 임박 · DUE 도래
 */
enum class DueState { NOT_SET, NO_BASE_RECORD, REVIEW, UPCOMING, SOON, DUE }

data class ScheduleStatus(
    val plan: MaintenancePlan,
    val baseEntryId: String?,
    val baseDate: LocalDate?,
    val baseKm: Double?,
    val nextKm: Double?,
    val nextDate: LocalDate?,
    val remainingKm: Double?,
    val daysLeft: Long?,
    val kmDue: Boolean,
    val dateDue: Boolean,
    /** 거리 주기는 있는데 기준 km나 현재 km가 없어 거리 조건을 계산하지 못한 상태. 날짜 조건은 그대로 유지된다. */
    val distancePending: Boolean,
    val state: DueState,
)

data class ScheduleConfig(val soonKm: Double = 1000.0, val soonDays: Long = 30, val regressionToleranceKm: Double = 0.5)

/**
 * 다음 거리 = 확인한 정비 주행거리 + 설정 주기. 다음 날짜 = 확인한 정비 날짜 + 설정 개월 수.
 * 도래 = 현재 km ≥ 다음 km 또는 오늘 ≥ 다음 날짜. 거리 잔여량을 날짜로 환산하지 않는다.
 */
object ScheduleCalculator {
    /** 이 계획의 기준이 되는 마지막 유효 정비. 취소·소급 기록이 바뀌면 여기서 다시 고른다. */
    fun baseEntry(plan: MaintenancePlan, entries: List<DiaryEntry>): DiaryEntry? =
        entries.filter { it.isConfirmed && it.vehicleId == plan.vehicleId && it.item == plan.item && countsFor(plan, it) }
            .maxWithOrNull(compareBy<DiaryEntry>({ it.performedOn }, { it.odometer?.value }, { it.confirmedAt }))

    private fun countsFor(plan: MaintenancePlan, e: DiaryEntry): Boolean = when (plan.kind) {
        PlanKind.REPLACE -> e.type == EntryType.MAINTENANCE                       // 점검만으로 교환 주기를 다시 시작하지 않는다
        PlanKind.INSPECT -> e.type == EntryType.MAINTENANCE || e.type == EntryType.INSPECTION
        PlanKind.NONE -> e.type == EntryType.MAINTENANCE || e.type == EntryType.REFILL
    }

    fun status(plan: MaintenancePlan, entries: List<DiaryEntry>, currentKm: Double?, today: LocalDate, cfg: ScheduleConfig = ScheduleConfig()): ScheduleStatus =
        status(plan, baseEntry(plan, entries), currentKm, today, cfg)

    /**
     * 기준 정비를 호출하는 쪽이 이미 골랐을 때. 항목 체계가 다른 앱(예: "패드 점검"과 "패드 교체"를 별도 항목으로 두는 앱)이
     * 자기 규칙으로 기준을 고르고, 다음 거리·날짜와 도래 판단만 core에 맡길 수 있다.
     */
    fun status(plan: MaintenancePlan, base: DiaryEntry?, currentKm: Double?, today: LocalDate, cfg: ScheduleConfig = ScheduleConfig()): ScheduleStatus {
        val baseKm = base?.odometer?.value
        val nextKm = if (base != null && baseKm != null && plan.intervalKm != null) baseKm + plan.intervalKm else null
        val nextDate = if (base != null && plan.intervalMonths != null) base.performedOn.plusMonths(plan.intervalMonths.toLong()) else null
        val remainingKm = if (nextKm != null && currentKm != null) nextKm - currentKm else null
        val daysLeft = nextDate?.let { ChronoUnit.DAYS.between(today, it) }
        val kmDue = remainingKm != null && remainingKm <= 0.0
        val dateDue = nextDate != null && !today.isBefore(nextDate)
        val distancePending = plan.intervalKm != null && (nextKm == null || currentKm == null)
        val regressed = currentKm != null && baseKm != null && currentKm < baseKm - cfg.regressionToleranceKm
        val state = when {
            !plan.isSet -> DueState.NOT_SET
            base == null -> DueState.NO_BASE_RECORD
            regressed -> DueState.REVIEW
            kmDue || dateDue -> DueState.DUE
            (remainingKm != null && remainingKm <= cfg.soonKm) || (daysLeft != null && daysLeft <= cfg.soonDays) -> DueState.SOON
            else -> DueState.UPCOMING
        }
        return ScheduleStatus(plan, base?.id, base?.performedOn, baseKm, nextKm, nextDate, remainingKm, daysLeft, kmDue, dateDue, distancePending, state)
    }

    /** 브리핑·"다음 관리는 언제야?" 답변 순서. 도래 → 임박 → 예정, 같은 상태면 날짜가 가까운 순. 거리와 날짜를 섞어 비교하지 않는다. */
    val urgency: Comparator<ScheduleStatus> = compareBy<ScheduleStatus>(
        { when (it.state) { DueState.DUE -> 0; DueState.REVIEW -> 1; DueState.SOON -> 2; DueState.UPCOMING -> 3; DueState.NO_BASE_RECORD -> 4; DueState.NOT_SET -> 5 } },
        { it.nextDate ?: LocalDate.MAX },
        { it.plan.item.ordinal },
    )
}
