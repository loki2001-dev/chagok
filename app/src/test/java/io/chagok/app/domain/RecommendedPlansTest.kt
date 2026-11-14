package io.chagok.app.domain

import io.chagok.app.data.Codec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecommendedPlansTest {
    private val store = MemoryStore()
    private val clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC)
    private val diary = DiaryService(store, clock)

    @Test fun existingPlansIncludingDisabledOnesSurviveAutomaticSetupAndProfileChanges() {
        diary.setPlan(Item.ENGINE_OIL, 8500, 8, "내 차 설명서")
        diary.setPlan(Item.SPARK_PLUG, null, null, "사용 안 함")
        val plans = diary.schedules(null).associateBy { it.plan.item }
        assertEquals(9, plans.size)
        assertEquals(8500, plans.getValue(Item.ENGINE_OIL).intervalKm)
        assertEquals(ScheduleState.NO_PLAN, plans.getValue(Item.SPARK_PLUG).state)
        assertEquals(10_000, plans.getValue(Item.CABIN_FILTER).intervalKm)
        assertEquals(6, plans.getValue(Item.CABIN_FILTER).plan.intervalMonths)
        assertNull(plans.getValue(Item.CABIN_FILTER).intervalMonths)
        assertNull(plans.getValue(Item.TRANSMISSION_OIL).intervalMonths)
        assertFalse(plans.containsKey(Item.DRIVE_BELT))
        assertTrue(diary.confirmedEntries().isEmpty())
        diary.updateProfile("차", Powertrain.EV, null)
        assertEquals(setOf(Item.CABIN_FILTER,Item.BRAKE_PAD_CHECK,Item.BRAKE_FLUID,Item.TIRE_REPLACE,Item.BATTERY_12V_REPLACE),
            diary.schedules(null).map { it.plan.item }.toSet())
        diary.updateProfile("차", Powertrain.HYBRID, null)
        assertEquals(plans, DiaryService(store, clock).schedules(null).associateBy { it.plan.item })
    }

    @Test fun firstCoolantScheduleSwitchesOnlyAfterConfirmedReplacement() {
        fun status() = diary.schedules(188_000.0).single { it.plan.item == Item.COOLANT }
        assertEquals(200_000, status().intervalKm)
        assertNull(status().intervalMonths)
        assertNull(status().nextDate)
        assertNull(status().nextKm)
        diary.setReportedServiceKm(30_000.0, 188_000.0)
        assertEquals(230_000.0, status().nextKm!!, 0.0)
        assertEquals(status().plan, Codec.plan(Codec.plan(status().plan)))
        val draft = diary.manualDraft(Item.COOLANT, diary.today(), null,
            Reading.Value(Observation(188_000.0, clock.instant())), "나")
        assertTrue(status().firstFill)
        assertTrue(diary.confirm(draft.id) is DiaryService.ConfirmResult.Saved)
        assertFalse(status().firstFill)
        assertEquals(228_000.0, status().nextKm!!, 0.0)
        assertEquals(diary.today().plusYears(2), status().nextDate)
        diary.cancel(draft.id)
        assertNull(status().nextDate)
        assertEquals(230_000.0, status().nextKm!!, 0.0)
    }

    @Test fun questionIsOncePerVehicleAndItsAnswerCanBeCorrectedLater() {
        assertTrue(diary.offerServiceMileage())
        val reopened = DiaryService(store, clock)
        reopened.selectUser("두번째 사용자")
        assertFalse(reopened.offerServiceMileage())
        fun oil() = reopened.schedules(47_000.0).single { it.plan.item == Item.ENGINE_OIL }
        assertNull(oil().nextKm)
        for (invalid in listOf(-1.0, Double.NaN, 47_001.0)) {
            assertThrows(IllegalArgumentException::class.java) { reopened.setReportedServiceKm(invalid, 47_000.0) }
        }
        reopened.setReportedServiceKm(0.0, 47_000.0)
        assertEquals(7_000.0, oil().nextKm!!, 0.0)
        reopened.setReportedServiceKm(42_000.0, 47_000.0)
        assertEquals(49_000.0, oil().nextKm!!, 0.0)
        assertNull(oil().nextDate)
        assertTrue(reopened.timeline().isEmpty())
    }

    @Test fun confirmedItemKeepsItsOwnDistanceAndDate() {
        val draft = diary.manualDraft(Item.ENGINE_OIL, diary.today(), null,
            Reading.Value(Observation(45_000.0, clock.instant())), "나")
        diary.confirm(draft.id)
        diary.setReportedServiceKm(40_000.0, 47_000.0)
        val schedules = diary.schedules(47_000.0).associateBy { it.plan.item }
        val oil = schedules.getValue(Item.ENGINE_OIL)
        assertEquals(52_000.0, oil.nextKm!!, 0.0)
        assertEquals(diary.today().plusMonths(6), oil.nextDate)
        assertEquals(50_000.0, schedules.getValue(Item.CABIN_FILTER).nextKm!!, 0.0)
        assertFalse(diary.offerServiceMileage())
    }

    @Test fun koreanMileageAnswersAcceptDistanceAndRejectUnrelatedSpeech() {
        listOf("사만 이천 킬로", "4만 2천 킬로", "42,000km", "42000입니다", "약 4.2만 키로요").forEach {
            assertEquals(it, 42_000.0, UtteranceParser.serviceMileage(it)!!, 0.0)
        }
        assertEquals(0.0, UtteranceParser.serviceMileage("0 km")!!, 0.0)
        listOf("몰라요", "-300km", "4만원", "3,5km", "40~50km", "").forEach {
            assertNull(it, UtteranceParser.serviceMileage(it))
        }
    }
}
