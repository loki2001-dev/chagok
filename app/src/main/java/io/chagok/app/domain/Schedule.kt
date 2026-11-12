package io.chagok.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 거리 또는 날짜 조건 하나의 상태 */
enum class ConditionState(val label: String) {
    NOT_SET("주기 미설정"),

    /** 기준 정비의 주행거리나 현재 주행거리가 없어 계산을 미룬다 */
    PENDING("계산 보류"),
    UPCOMING("예정"),
    SOON("임박"),
    DUE("도래"),
}

enum class ScheduleState(val label: String) {
    NO_PLAN("주기 미설정"),
    NO_RECORD("기준 기록 없음"),
    UPCOMING("예정"),
    SOON("임박"),
    DUE("도래"),
}

data class ScheduleStatus(
    val plan: MaintenancePlan,
    val basis: DiaryEntry?,
    val nextKm: Double?,
    val nextDate: LocalDate?,
    val kmState: ConditionState,
    val dateState: ConditionState,
    val remainingKm: Double?,
    val remainingDays: Long?,
    /** 현재 km가 기준 정비 km보다 작다. 차량 변경·계기판 교체 등 검토가 필요하다 */
    val odometerReversed: Boolean,
) {
    val state: ScheduleState
        get() = when {
            plan.intervalKm == null && plan.intervalMonths == null -> ScheduleState.NO_PLAN
            basis == null -> ScheduleState.NO_RECORD
            kmState == ConditionState.DUE || dateState == ConditionState.DUE -> ScheduleState.DUE
            kmState == ConditionState.SOON || dateState == ConditionState.SOON -> ScheduleState.SOON
            else -> ScheduleState.UPCOMING
        }
}

/**
 * 기획서 5장 '거리와 기간 조건'.
 * 다음 거리 = 확인한 정비 주행거리 + 설정 주기, 다음 날짜 = 확인한 정비 날짜 + 설정 개월 수.
 * 둘 중 하나라도 충족하면 도래다. 거리 잔여량을 날짜로 환산하지 않는다.
 */
object ScheduleCalculator {
    /** 임박 기준. 검토용 기본값이며 사용자 설정으로 바꿀 수 있다 */
    const val SOON_KM = 1_000.0
    const val SOON_DAYS = 30L

    /** 기준 기록: 해당 항목의 마지막 유효(확정) 정비. 수행일, 주행거리, 작성 순으로 가장 늦은 것 */
    fun basisOf(item: Item, entries: List<DiaryEntry>): DiaryEntry? =
        entries.asSequence()
            .filter { it.item == item && it.status == EntryStatus.CONFIRMED }
            .maxWithOrNull(
                compareBy<DiaryEntry> { it.performedOn }
                    .thenBy { it.odometer?.value ?: -1.0 }
                    .thenBy { it.createdAt },
            )

    fun evaluate(
        plan: MaintenancePlan,
        entries: List<DiaryEntry>,
        currentKm: Double?,
        today: LocalDate,
    ): ScheduleStatus {
        val basis = basisOf(plan.item, entries)
        val basisKm = basis?.odometer?.value

        val nextKm = if (basisKm != null && plan.intervalKm != null) basisKm + plan.intervalKm else null
        // plusMonths는 말일을 맞춰 준다: 1월 31일 + 1개월 = 2월 28일(윤년 29일)
        val nextDate = if (basis != null && plan.intervalMonths != null) basis.performedOn.plusMonths(plan.intervalMonths.toLong()) else null

        val reversed = basisKm != null && currentKm != null && currentKm < basisKm
        val remainingKm = if (nextKm != null && currentKm != null && !reversed) nextKm - currentKm else null
        val kmState = when {
            plan.intervalKm == null -> ConditionState.NOT_SET
            basis == null || nextKm == null || currentKm == null || reversed -> ConditionState.PENDING
            currentKm >= nextKm -> ConditionState.DUE
            nextKm - currentKm <= SOON_KM -> ConditionState.SOON
            else -> ConditionState.UPCOMING
        }

        val remainingDays = nextDate?.let { ChronoUnit.DAYS.between(today, it) }
        val dateState = when {
            plan.intervalMonths == null -> ConditionState.NOT_SET
            nextDate == null -> ConditionState.PENDING
            !today.isBefore(nextDate) -> ConditionState.DUE
            remainingDays!! <= SOON_DAYS -> ConditionState.SOON
            else -> ConditionState.UPCOMING
        }

        return ScheduleStatus(plan, basis, nextKm, nextDate, kmState, dateState, remainingKm, remainingDays, reversed)
    }
}
