package io.chagok.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** 기획서 11장 '주기 계산', 5장 '비용 합계' */
class ScheduleAndLedgerTest {
    private val plan = MaintenancePlan("car", Item.ENGINE_OIL, 10_000, 12, "시연용 예시값", LocalDate.of(2026, 1, 1))

    private fun entry(
        date: LocalDate,
        km: Double?,
        cost: Long? = null,
        status: EntryStatus = EntryStatus.CONFIRMED,
        item: Item = Item.ENGINE_OIL,
        id: String = "$item-$date-$km-$status",
    ) = DiaryEntry(
        id = id, vehicleId = "car", item = item, performedOn = date, createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        odometer = km?.let { FieldEvidence(it, "km", Source.VEHICLE) }, cost = cost, status = status, author = "나",
    )

    private val base = entry(LocalDate.of(2026, 11, 8), 47_200.0)

    @Test
    fun `거리 먼저 도래`() {
        val s = ScheduleCalculator.evaluate(plan, listOf(base), 57_300.0, LocalDate.of(2027, 3, 1))
        assertEquals(57_200.0, s.nextKm!!, 0.0)
        assertEquals(ConditionState.DUE, s.kmState)
        assertEquals(ConditionState.UPCOMING, s.dateState)
        assertEquals(ScheduleState.DUE, s.state)
    }

    @Test
    fun `날짜 먼저 도래`() {
        val s = ScheduleCalculator.evaluate(plan, listOf(base), 50_000.0, LocalDate.of(2027, 11, 9))
        assertEquals(LocalDate.of(2027, 11, 8), s.nextDate)
        assertEquals(ConditionState.UPCOMING, s.kmState)
        assertEquals(ConditionState.DUE, s.dateState)
        assertEquals(ScheduleState.DUE, s.state)
    }

    @Test
    fun `정확히 도래`() {
        val s = ScheduleCalculator.evaluate(plan, listOf(base), 57_200.0, LocalDate.of(2027, 11, 8))
        assertEquals(ConditionState.DUE, s.kmState)
        assertEquals(ConditionState.DUE, s.dateState)
    }

    @Test
    fun `임박`() {
        val s = ScheduleCalculator.evaluate(plan, listOf(base), 56_500.0, LocalDate.of(2027, 1, 1))
        assertEquals(ScheduleState.SOON, s.state)
        assertEquals(700.0, s.remainingKm!!, 0.0)
    }

    @Test
    fun `말일과 윤년`() {
        val one = plan.copy(intervalMonths = 1)
        val jan31 = entry(LocalDate.of(2028, 1, 31), 1_000.0)
        assertEquals(LocalDate.of(2028, 2, 29), ScheduleCalculator.evaluate(one, listOf(jan31), null, LocalDate.of(2028, 2, 1)).nextDate)
        val feb29 = entry(LocalDate.of(2028, 2, 29), 1_000.0)
        assertEquals(LocalDate.of(2029, 2, 28), ScheduleCalculator.evaluate(plan, listOf(feb29), null, LocalDate.of(2028, 3, 1)).nextDate)
    }

    @Test
    fun `주기 미설정`() {
        val s = ScheduleCalculator.evaluate(plan.copy(intervalKm = null, intervalMonths = null), listOf(base), 50_000.0, LocalDate.of(2027, 1, 1))
        assertEquals(ScheduleState.NO_PLAN, s.state)
    }

    @Test
    fun `주행거리가 없으면 거리 조건은 보류, 날짜 조건은 유지`() {
        val s = ScheduleCalculator.evaluate(plan, listOf(base), null, LocalDate.of(2027, 1, 1))
        assertEquals(ConditionState.PENDING, s.kmState)
        assertEquals(ConditionState.UPCOMING, s.dateState)
        val noKmBasis = ScheduleCalculator.evaluate(plan, listOf(entry(LocalDate.of(2026, 11, 8), null)), 50_000.0, LocalDate.of(2027, 1, 1))
        assertEquals(ConditionState.PENDING, noKmBasis.kmState)
        assertNull(noKmBasis.nextKm)
    }

    @Test
    fun `거리 역행은 검토 대상`() {
        val s = ScheduleCalculator.evaluate(plan, listOf(base), 30_000.0, LocalDate.of(2027, 1, 1))
        assertTrue(s.odometerReversed)
        assertEquals(ConditionState.PENDING, s.kmState)
    }

    @Test
    fun `취소된 기록은 기준에서 빠지고 마지막 유효 정비로 다시 계산한다`() {
        val older = entry(LocalDate.of(2026, 5, 1), 40_000.0)
        val cancelled = base.copy(status = EntryStatus.CANCELLED)
        val s = ScheduleCalculator.evaluate(plan, listOf(older, cancelled), 45_000.0, LocalDate.of(2026, 11, 10))
        assertEquals(older.id, s.basis!!.id)
        assertEquals(50_000.0, s.nextKm!!, 0.0)
        assertEquals(ScheduleState.NO_RECORD, ScheduleCalculator.evaluate(plan, listOf(cancelled), 45_000.0, LocalDate.of(2026, 11, 10)).state)
    }

    @Test
    fun `소급 기록은 수행일 기준`() {
        val late = entry(LocalDate.of(2026, 11, 1), 46_800.0)
        val s = ScheduleCalculator.evaluate(plan, listOf(base, late), 47_300.0, LocalDate.of(2026, 11, 10))
        assertEquals(base.id, s.basis!!.id)
    }

    @Test
    fun `비용은 확정 기록만, 미입력은 따로 센다`() {
        val nov = YearMonth.of(2026, 11)
        val entries = listOf(
            entry(LocalDate.of(2026, 11, 8), 1.0, cost = 80_000),
            entry(LocalDate.of(2026, 11, 9), 2.0, cost = 50_000, item = Item.REFUEL),
            entry(LocalDate.of(2026, 11, 10), 3.0, cost = null, item = Item.TIRE_ROTATION),
            entry(LocalDate.of(2026, 11, 11), 4.0, cost = 70_000, status = EntryStatus.CANCELLED),
            entry(LocalDate.of(2026, 11, 12), 5.0, cost = 30_000, status = EntryStatus.DRAFT),
            entry(LocalDate.of(2026, 10, 30), 6.0, cost = 10_000),
        )
        val m = Ledger.month(entries, nov)
        assertEquals(130_000L, m.total)
        assertEquals(1, m.missingCostCount)
        assertEquals(3, m.entryCount)
        assertEquals(80_000L, m.byItem[Item.ENGINE_OIL])
        assertEquals(listOf(nov, YearMonth.of(2026, 10)), Ledger.months(entries))
    }
}
