package io.chagok.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 앱 도메인이 `:core`에 연결된 뒤에도 공개 동작이 유지되는지, 그리고 core 쪽 개선이 앱 항목으로 제대로 옮겨지는지 본다.
 */
class CoreBridgeTest {
    private val today = LocalDate.of(2026, 11, 8)
    private val parser = UtteranceParser { today }
    private fun record(text: String) = parser.parse(text) as SpokenIntent.Record

    // ── 발화: core가 더 잘 구분하는 것 ──

    @Test fun replacementsTheOldParserLumpedIntoOther() {
        assertEquals(Item.BRAKE_PAD_REPLACE, record("브레이크 패드 교체했어 15만 원").item)
        assertEquals(150_000L, record("브레이크 패드 교체했어 15만 원").cost)
        assertEquals(Item.BATTERY_12V_REPLACE, record("배터리 교체했어 12만 원").item)
        assertEquals(Item.WIPER_REPLACE, record("와이퍼 갈았어").item)
        assertEquals(Item.CABIN_FILTER, record("에어컨 필터 바꿨어").item)
    }

    @Test fun inspectionWithoutAnAppCheckItemStaysOtherWithMemo() {
        val r = record("엔진오일 점검했어")
        assertEquals("교환 기록으로 저장하지 않는다", Item.OTHER, r.item)
        assertEquals("엔진오일 점검했어", r.memo)
    }

    @Test fun singleWheelAndAllWheels() {
        assertEquals(setOf(Wheel.FRONT_LEFT), record("운전석 앞 타이어 교체했어").wheels)
        assertEquals(Wheel.entries.toSet(), record("앞뒤 타이어 교체했어").wheels)
        assertTrue("위치 교환에는 바퀴를 붙이지 않는다", record("앞 타이어 위치 교환했어").wheels.isEmpty())
    }

    @Test fun otherMaintenanceKeepsTheNormalizedText() {
        val r = record("정비소에서 하체 수리했어. 25만 원!")
        assertEquals(Item.OTHER, r.item); assertEquals(250_000L, r.cost); assertEquals("정비소에서 하체 수리했어 25만 원", r.memo)
        assertTrue(parser.parse("음악 틀어줘") is SpokenIntent.Unknown)
    }

    @Test fun amountsAreSharedWithTheConversationScreen() {
        assertEquals(listOf(12_000L), UtteranceParser.amounts("1만 2천 원"))
        assertEquals(15_000L, UtteranceParser.parseNumber("1.5만"))
        assertEquals(null, UtteranceParser.parseNumber("공"))
    }

    // ── 일정: core 계산이 기존 앱 공식과 같은지 ──

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private fun entry(item: Item, date: LocalDate, km: Double?) = DiaryEntry(
        id = "e$date$km", vehicleId = "car", item = item, performedOn = date, createdAt = t0,
        odometer = km?.let { FieldEvidence(it, "km", Source.VEHICLE, t0) }, cost = null, status = EntryStatus.CONFIRMED, author = "A",
    )

    /** 연결 전 앱 구현을 그대로 옮긴 기준식. core로 바꾼 결과와 한 줄씩 비교한다. */
    private fun legacy(plan: MaintenancePlan, entries: List<DiaryEntry>, currentKm: Double?, today: LocalDate): ScheduleStatus {
        val basis = ScheduleCalculator.basisOf(plan.item, entries)
        val basisKm = basis?.odometer?.value
        val nextKm = if (basisKm != null && plan.intervalKm != null) basisKm + plan.intervalKm else null
        val nextDate = if (basis != null && plan.intervalMonths != null) basis.performedOn.plusMonths(plan.intervalMonths.toLong()) else null
        val reversed = basisKm != null && currentKm != null && currentKm < basisKm
        val remainingKm = if (nextKm != null && currentKm != null && !reversed) nextKm - currentKm else null
        val kmState = when {
            plan.intervalKm == null -> ConditionState.NOT_SET
            basis == null || nextKm == null || currentKm == null || reversed -> ConditionState.PENDING
            currentKm >= nextKm -> ConditionState.DUE
            nextKm - currentKm <= ScheduleCalculator.SOON_KM -> ConditionState.SOON
            else -> ConditionState.UPCOMING
        }
        val remainingDays = nextDate?.let { ChronoUnit.DAYS.between(today, it) }
        val dateState = when {
            plan.intervalMonths == null -> ConditionState.NOT_SET
            nextDate == null -> ConditionState.PENDING
            !today.isBefore(nextDate) -> ConditionState.DUE
            remainingDays!! <= ScheduleCalculator.SOON_DAYS -> ConditionState.SOON
            else -> ConditionState.UPCOMING
        }
        return ScheduleStatus(plan, basis, nextKm, nextDate, kmState, dateState, remainingKm, remainingDays, reversed)
    }

    @Test fun scheduleMatchesTheLegacyFormulaEverywhere() {
        val bases = listOf(
            emptyList(),
            listOf(entry(Item.ENGINE_OIL, today, 47_200.0)),
            listOf(entry(Item.ENGINE_OIL, today, null)),
            listOf(entry(Item.ENGINE_OIL, LocalDate.of(2026, 1, 31), 30_000.0), entry(Item.ENGINE_OIL, LocalDate.of(2025, 5, 1), 20_000.0)),
            listOf(entry(Item.ENGINE_OIL, LocalDate.of(2028, 2, 29), 30_000.0)),
            listOf(entry(Item.BRAKE_PAD_CHECK, today, 47_200.0)),
        )
        val plans = listOf(
            MaintenancePlan("car", Item.ENGINE_OIL, 10_000, 12, "manual", today),
            MaintenancePlan("car", Item.ENGINE_OIL, 10_000, null, "manual", today),
            MaintenancePlan("car", Item.ENGINE_OIL, null, 1, "manual", today),
            MaintenancePlan("car", Item.ENGINE_OIL, null, null, "manual", today),
        )
        val kms = listOf(null, 0.0, 29_999.0, 30_000.0, 39_000.0, 40_000.0, 47_199.9, 47_200.0, 56_200.0, 56_500.0, 57_200.0, 60_000.0)
        val days = listOf(today, LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 28), LocalDate.of(2027, 10, 9), LocalDate.of(2027, 11, 8), LocalDate.of(2029, 2, 28))
        var n = 0
        for (e in bases) for (p in plans) for (km in kms) for (d in days) {
            assertEquals("entries=$e plan=$p km=$km day=$d", legacy(p, e, km, d), ScheduleCalculator.evaluate(p, e, km, d)); n++
        }
        assertEquals(6 * 4 * 12 * 6, n)
    }
}
