package io.chagok.app.domain

import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.model.EvidenceSource

import ai.chagok.core.schedule.ScheduleConfig
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import ai.chagok.core.diary.DiaryEntry as CoreEntry
import ai.chagok.core.diary.EntryStatus as CoreStatus
import ai.chagok.core.model.FieldEvidence as CoreEvidence
import ai.chagok.core.schedule.MaintenancePlan as CorePlan
import ai.chagok.core.schedule.ScheduleCalculator as CoreSchedule

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
    val firstFill get() = basis == null && plan.item == Item.COOLANT
    val intervalKm get() = if (firstFill) plan.initialKm else plan.intervalKm
    val intervalMonths get() = plan.intervalMonths.takeIf { basis != null }
    val state: ScheduleState
        get() = when {
            plan.intervalKm == null && plan.intervalMonths == null &&
                !(firstFill && intervalKm != null) -> ScheduleState.NO_PLAN
            basis == null && nextKm == null -> ScheduleState.NO_RECORD
            kmState == ConditionState.DUE || dateState == ConditionState.DUE -> ScheduleState.DUE
            kmState == ConditionState.SOON || dateState == ConditionState.SOON -> ScheduleState.SOON
            else -> ScheduleState.UPCOMING
        }

    /**
     * 기준 정비 이후 주행한 거리. 거리 조건을 계산할 수 있을 때만 값이 있다.
     * 기록이 없어도 사용자가 알려 준 마지막 정비 주행거리로 다음 거리가 잡히면 계산된다.
     */
    val usedKm: Double?
        get() = intervalKm?.let { interval -> remainingKm?.let { interval - it } }

    /** 적용 중인 주행 주기 대비 마모도(%). 얼마나 넘겼는지 보이도록 100을 넘을 수 있다. */
    val wearPercent: Int?
        get() = intervalKm?.takeIf { it > 0 }?.let { interval ->
            usedKm?.let { (it * 100 / interval).toInt().coerceAtLeast(0) }
        }

    /** 계산 기준일. 날짜 조건이 있을 때만 알 수 있다. */
    val asOf: LocalDate?
        get() = if (nextDate != null && remainingDays != null) nextDate.minusDays(remainingDays) else null

    /** 권장 기간 대비 사용 기간(%). 100을 넘을 수 있다. */
    val agePercent: Int?
        get() {
            val today = asOf ?: return null
            if (basis == null || nextDate == null) return null
            val interval = ChronoUnit.DAYS.between(basis.performedOn, nextDate)
            if (interval <= 0) return null
            return (ChronoUnit.DAYS.between(basis.performedOn, today) * 100.0 / interval).toInt().coerceAtLeast(0)
        }

    /** 사용한 주기 비율. 거리와 날짜 중 진행이 빠른 쪽을 표시한다. */
    val progressPercent: Int?
        get() = listOfNotNull(wearPercent, agePercent).maxOrNull()?.coerceIn(0, 100)
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

    /**
     * 기준 기록은 앱 항목 체계로 고르고([basisOf]), 다음 거리·날짜와 도래 판단은 `:core`에 맡긴다.
     * 임박 기준과 역행 판단(허용 오차 0)은 앱의 기존 값을 그대로 넘긴다.
     */
    fun evaluate(
        plan: MaintenancePlan,
        entries: List<DiaryEntry>,
        currentKm: Double?,
        today: LocalDate,
        reportedServiceKm: Double? = null,
    ): ScheduleStatus {
        val basis = basisOf(plan.item, entries)
        val firstFill = plan.item == Item.COOLANT && basis == null
        val kmInterval = if (firstFill) plan.initialKm else plan.intervalKm

        val basisKm = if (basis == null) reportedServiceKm else basis.odometer?.value
        val core = CoreSchedule.status(corePlan(plan), basis?.let(::coreBase), currentKm, today, CORE_CONFIG)
        val nextKm = if (basis == null && basisKm != null && kmInterval != null) basisKm + kmInterval else core.nextKm
        val reversed = basisKm != null && currentKm != null && currentKm < basisKm
        val remainingKm = if (!reversed && nextKm != null && currentKm != null) nextKm - currentKm else null
        val kmState = when {
            kmInterval == null -> ConditionState.NOT_SET
            nextKm == null || currentKm == null || reversed -> ConditionState.PENDING
            currentKm >= nextKm -> ConditionState.DUE
            nextKm - currentKm <= SOON_KM -> ConditionState.SOON
            else -> ConditionState.UPCOMING
        }
        val dateState = when {
            plan.intervalMonths == null -> ConditionState.NOT_SET
            core.nextDate == null -> ConditionState.PENDING
            core.dateDue -> ConditionState.DUE
            core.daysLeft!! <= SOON_DAYS -> ConditionState.SOON
            else -> ConditionState.UPCOMING
        }
        return ScheduleStatus(plan, basis, nextKm, core.nextDate, kmState, dateState, remainingKm, core.daysLeft, reversed)
    }

    private val CORE_CONFIG = ScheduleConfig(soonKm = SOON_KM, soonDays = SOON_DAYS, regressionToleranceKm = 0.0)

    /** core 계산에는 주기와 설정 정보만 쓰인다. 항목은 표시용이라 가까운 것으로 옮긴다. */
    private fun corePlan(p: MaintenancePlan) = CorePlan(
        vehicleId = p.vehicleId, item = MaintenanceItem.OTHER,
        intervalKm = p.intervalKm?.toDouble(), intervalMonths = p.intervalMonths, source = p.source, setOn = p.setOn,
    )

    private fun coreBase(e: DiaryEntry) = CoreEntry(
        id = e.id, vehicleId = e.vehicleId, type = EntryType.MAINTENANCE, item = MaintenanceItem.OTHER,
        performedOn = e.performedOn, createdAt = e.createdAt, authorId = e.author,
        odometer = e.odometer?.let { CoreEvidence(it.value, it.unit, EvidenceSource.USER, it.receivedAt) },
        status = CoreStatus.CONFIRMED, confirmedAt = e.updatedAt,
    )
}
