package ai.chagok.core

import ai.chagok.core.diary.DiaryEntry
import ai.chagok.core.diary.EntryStatus
import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.diary.Money
import ai.chagok.core.ledger.CostLedger
import ai.chagok.core.model.EvidenceSource
import ai.chagok.core.model.FieldEvidence
import ai.chagok.core.schedule.DueState
import ai.chagok.core.schedule.MaintenancePlan
import ai.chagok.core.schedule.ScheduleCalculator
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleAndLedgerTest {
    private var seq = 0
    private fun entry(
        item: MaintenanceItem?, date: LocalDate, km: Double? = null, type: EntryType = EntryType.MAINTENANCE,
        cost: Long? = null, currency: String = "KRW", status: EntryStatus = EntryStatus.CONFIRMED,
    ) = DiaryEntry(
        id = "e${++seq}", vehicleId = Fx.CAR, type = type, item = item, performedOn = date, createdAt = Fx.T0, authorId = "minji",
        cost = cost?.let { Money(it, currency) }, odometer = km?.let { FieldEvidence(it, "km", EvidenceSource.VEHICLE, Fx.T0) },
        status = status, confirmedAt = if (status == EntryStatus.CONFIRMED) Fx.T0.plusSeconds(seq.toLong()) else null,
    )
    private val oil = MaintenancePlan(Fx.CAR, MaintenanceItem.ENGINE_OIL, 10_000.0, 12, "사용자 확인: 매뉴얼", Fx.TODAY)
    private val base = entry(MaintenanceItem.ENGINE_OIL, Fx.TODAY, 47_200.0)
    private fun st(km: Double?, today: LocalDate, entries: List<DiaryEntry> = listOf(base), plan: MaintenancePlan = oil) =
        ScheduleCalculator.status(plan, entries, km, today)

    // ── 일정 ──

    @Test fun nextIsBasePlusInterval() {
        val s = st(48_000.0, LocalDate.of(2026, 12, 1))
        assertEquals(57_200.0, s.nextKm); assertEquals(LocalDate.of(2027, 11, 8), s.nextDate)
        assertEquals(9_200.0, s.remainingKm); assertEquals(DueState.UPCOMING, s.state); assertEquals(base.id, s.baseEntryId)
    }

    @Test fun distanceFirstDateFirstAndExactlyDue() {
        val km = st(57_300.0, LocalDate.of(2027, 3, 1))
        assertTrue(km.kmDue && !km.dateDue); assertEquals(DueState.DUE, km.state)
        val date = st(50_000.0, LocalDate.of(2027, 11, 8))
        assertTrue(date.dateDue && !date.kmDue, "정확히 그날이면 도래"); assertEquals(DueState.DUE, date.state)
        assertTrue(st(57_200.0, LocalDate.of(2027, 3, 1)).kmDue, "정확히 그 km면 도래")
        assertFalse(st(50_000.0, LocalDate.of(2027, 11, 7)).dateDue)
    }

    @Test fun soonByEitherCondition() {
        assertEquals(DueState.SOON, st(56_500.0, LocalDate.of(2027, 3, 1)).state, "1,000km 이내")
        assertEquals(DueState.SOON, st(50_000.0, LocalDate.of(2027, 10, 20)).state, "30일 이내")
    }

    @Test fun monthEndAndLeapYear() {
        fun next(d: LocalDate, months: Int) = st(null, d, listOf(entry(MaintenanceItem.ENGINE_OIL, d)), oil.copy(intervalMonths = months)).nextDate
        assertEquals(LocalDate.of(2026, 2, 28), next(LocalDate.of(2026, 1, 31), 1), "말일은 다음 달 말일로")
        assertEquals(LocalDate.of(2028, 2, 29), next(LocalDate.of(2028, 1, 31), 1), "윤년")
        assertEquals(LocalDate.of(2029, 2, 28), next(LocalDate.of(2028, 2, 29), 12), "윤일 + 12개월")
    }

    @Test fun missingValuesAndNotSet() {
        val noKmNow = st(null, LocalDate.of(2027, 11, 9))
        assertTrue(noKmNow.distancePending, "현재 km가 없으면 거리 조건 보류"); assertEquals(DueState.DUE, noKmNow.state, "날짜 조건은 유지")
        val noBaseKm = st(60_000.0, Fx.TODAY, listOf(entry(MaintenanceItem.ENGINE_OIL, Fx.TODAY)))
        assertNull(noBaseKm.nextKm); assertTrue(noBaseKm.distancePending); assertEquals(LocalDate.of(2027, 11, 8), noBaseKm.nextDate)
        assertEquals(DueState.NOT_SET, st(48_000.0, Fx.TODAY, plan = oil.copy(intervalKm = null, intervalMonths = null)).state)
        assertEquals(DueState.NO_BASE_RECORD, st(48_000.0, Fx.TODAY, entries = emptyList()).state)
        val kmOnly = st(48_000.0, Fx.TODAY, plan = oil.copy(intervalMonths = null))
        assertNull(kmOnly.nextDate); assertEquals(57_200.0, kmOnly.nextKm)
    }

    @Test fun callerChosenBaseGivesTheSameArithmetic() {
        val viaEntries = st(56_500.0, LocalDate.of(2027, 3, 1))
        val viaBase = ScheduleCalculator.status(oil, base, 56_500.0, LocalDate.of(2027, 3, 1))
        assertEquals(viaEntries, viaBase, "다른 항목 체계를 쓰는 앱이 기준만 골라 넘겨도 계산은 같다")
        assertEquals(DueState.NO_BASE_RECORD, ScheduleCalculator.status(oil, null as DiaryEntry?, 50_000.0, Fx.TODAY).state)
    }

    @Test fun odometerRegressionIsReview() {
        assertEquals(DueState.REVIEW, st(40_000.0, Fx.TODAY).state)
    }

    @Test fun baseIsLastValidMaintenanceAfterCancelOrBackdate() {
        val older = entry(MaintenanceItem.ENGINE_OIL, LocalDate.of(2026, 5, 1), 40_000.0)
        val cancelled = base.copy(status = EntryStatus.CANCELLED)
        val backdated = entry(MaintenanceItem.ENGINE_OIL, LocalDate.of(2026, 1, 1), 35_000.0)
        val s = st(48_000.0, Fx.TODAY, listOf(older, cancelled, backdated))
        assertEquals(older.id, s.baseEntryId, "취소는 빼고, 나중에 입력한 과거 기록은 더 오래된 날이라 기준이 아니다")
        assertEquals(50_000.0, s.nextKm); assertEquals(LocalDate.of(2027, 5, 1), s.nextDate)
        val draft = entry(MaintenanceItem.ENGINE_OIL, Fx.TODAY, 47_000.0, status = EntryStatus.DRAFT)
        assertEquals(older.id, st(48_000.0, Fx.TODAY, listOf(older, draft)).baseEntryId, "초안은 기준이 아니다")
    }

    @Test fun inspectionResetsInspectPlansOnly() {
        val insp = entry(MaintenanceItem.ENGINE_OIL, LocalDate.of(2027, 1, 1), 50_000.0, type = EntryType.INSPECTION)
        assertEquals(base.id, st(51_000.0, LocalDate.of(2027, 1, 2), listOf(base, insp)).baseEntryId, "점검만으로 교환 주기를 다시 시작하지 않는다")
        val pad = MaintenancePlan(Fx.CAR, MaintenanceItem.BRAKE_PAD, null, 6, "정비소 안내", Fx.TODAY)
        val padInsp = entry(MaintenanceItem.BRAKE_PAD, LocalDate.of(2026, 10, 1), type = EntryType.INSPECTION)
        val s = ScheduleCalculator.status(pad, listOf(padInsp), null, Fx.TODAY)
        assertEquals(padInsp.id, s.baseEntryId); assertEquals(LocalDate.of(2027, 4, 1), s.nextDate)
    }

    @Test fun urgencyOrdering() {
        val rot = MaintenancePlan(Fx.CAR, MaintenanceItem.TIRE_ROTATION, 10_000.0, null, "매뉴얼", Fx.TODAY)
        val rotBase = entry(MaintenanceItem.TIRE_ROTATION, LocalDate.of(2026, 1, 1), 38_000.0)
        val entries = listOf(base, rotBase)
        val list = listOf(oil, rot).map { ScheduleCalculator.status(it, entries, 48_500.0, Fx.TODAY) }.sortedWith(ScheduleCalculator.urgency)
        assertEquals(listOf(MaintenanceItem.TIRE_ROTATION, MaintenanceItem.ENGINE_OIL), list.map { it.plan.item }, "도래한 위치 교환이 먼저")
    }

    // ── 차계부 ──

    @Test fun ledgerCountsConfirmedOnlyAndKeepsMissingSeparate() {
        val nov = LocalDate.of(2026, 11, 8); val oct = LocalDate.of(2026, 10, 15)
        val es = listOf(
            entry(MaintenanceItem.ENGINE_OIL, nov, cost = 80_000),
            entry(null, nov, type = EntryType.FUEL, cost = 50_000),
            entry(MaintenanceItem.TIRE_ROTATION, nov),                                   // 금액 미입력
            entry(MaintenanceItem.WIPER, nov, cost = 30_000, status = EntryStatus.CANCELLED),
            entry(MaintenanceItem.WIPER, nov, cost = 10_000, status = EntryStatus.DRAFT),
            entry(MaintenanceItem.WIPER, oct, cost = 20_000),
            entry(MaintenanceItem.OTHER, nov, cost = 30, currency = "USD"),
            entry(null, nov, type = EntryType.SYMPTOM_MEMO, cost = 99_999),
        )
        val all = CostLedger.summarize(es, Fx.CAR)
        assertEquals(150_000L, all.total(), "확정만, 취소·초안·메모 제외")
        assertEquals(1, all.missingCostCount, "미입력은 0원이 아니라 따로 센다")
        assertEquals(130_000L, all.total(YearMonth.of(2026, 11))); assertEquals(20_000L, all.total(YearMonth.of(2026, 10)))
        assertEquals(30L, all.total("USD"), "통화는 섞지 않는다")
        assertEquals(80_000L, all.byCategory()["엔진오일"]); assertEquals(50_000L, all.byCategory()["주유"])
        val novOnly = CostLedger.summarize(es, Fx.CAR, YearMonth.of(2026, 11))
        assertEquals(130_000L, novOnly.total()); assertEquals(1, novOnly.missingCostCount)
        assertEquals(0L, CostLedger.summarize(es, "other-car").total())
    }
}
