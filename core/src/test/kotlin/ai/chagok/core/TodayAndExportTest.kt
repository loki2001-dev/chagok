package ai.chagok.core

import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.schedule.DueState
import ai.chagok.core.schedule.PlanNotify
import ai.chagok.core.share.CheckItem
import ai.chagok.core.share.ExportSpec
import ai.chagok.core.share.OdometerState
import ai.chagok.core.share.ReportExporter
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayAndExportTest {
    private fun Harness.today() = run { cmd(Command.OpenToday(Fx.CAR, "minji")); last<EngineEvent.TodayReady>().briefing }

    @Test fun odometerStates() = runTest {
        val h = Harness(this)
        assertEquals(OdometerState.NONE, h.today().odometer.state)
        h.obs(Fx.odo(47_200.0, Fx.T0))
        assertEquals(OdometerState.RECEIVED, h.today().odometer.state)
        h.clock.advance(Duration.ofMinutes(30))
        val stale = h.today().odometer
        assertEquals(OdometerState.STALE, stale.state); assertEquals(Fx.T0, stale.receivedAt, "마지막 수신 시각을 보여 준다")
    }

    @Test fun nextCardRespectsSnoozeAndOff() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0))
        h.cmd(Command.ManualDraft(Fx.CAR, "minji", EntryType.MAINTENANCE, MaintenanceItem.ENGINE_OIL, performedOn = LocalDate.of(2025, 10, 1), cost = 70_000))
        val oilId = h.last<EngineEvent.DraftReady>().entry.id
        h.cmd(Command.EditEntry(oilId, "minji", EntryEdit(odometerKm = 36_000.0)))
        h.cmd(Command.ConfirmEntry(oilId, "minji"))
        h.record("타이어 위치 교환했어")
        h.cmd(Command.SetPlan(Fx.CAR, MaintenanceItem.ENGINE_OIL, 10_000.0, 12, "매뉴얼"))
        h.cmd(Command.SetPlan(Fx.CAR, MaintenanceItem.TIRE_ROTATION, 10_000.0, null, "매뉴얼"))

        val b = h.today()
        assertEquals(MaintenanceItem.ENGINE_OIL, b.next!!.plan.item); assertEquals(DueState.DUE, b.next!!.state)
        assertTrue(b.next!!.kmDue && b.next!!.dateDue)
        assertEquals(2, b.schedule.size, "같은 항목은 한 카드")

        h.cmd(Command.SnoozePlan(Fx.CAR, MaintenanceItem.ENGINE_OIL, Fx.TODAY.plusDays(1)))
        assertEquals(MaintenanceItem.TIRE_ROTATION, h.today().next!!.plan.item, "미룬 항목은 오늘 카드에서 빠진다")
        assertEquals(MaintenanceItem.ENGINE_OIL, h.today().schedule.first().plan.item, "일정 목록에는 남는다")
        h.clock.advance(Duration.ofDays(1))
        assertEquals(MaintenanceItem.ENGINE_OIL, h.today().next!!.plan.item, "미룬 날이 지나면 돌아온다")
        h.cmd(Command.SetPlanNotify(Fx.CAR, MaintenanceItem.ENGINE_OIL, PlanNotify.OFF))
        assertEquals(MaintenanceItem.TIRE_ROTATION, h.today().next!!.plan.item)
        assertTrue(h.repo.entries().none { it.performedOn.isAfter(Fx.TODAY) }, "알림 확인만으로 정비 완료를 만들지 않는다")
    }

    @Test fun toCheckListsDraftsAndReviews() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0.minusSeconds(10)))
        h.say("와이퍼 점검했어")
        h.obs(Fx.odo(100.0, Fx.T0))
        val check = h.today().toCheck
        assertTrue(check.any { it is CheckItem.OdometerReview && it.observedKm == 100.0 })
        assertTrue(check.any { it is CheckItem.PendingDraft && it.entry.item == MaintenanceItem.WIPER })
        assertEquals(OdometerState.REVIEW, h.today().odometer.state)
    }

    @Test fun familySeesWhatOthersRecordedSinceLastLook() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0))
        h.record("타이어 위치 교환했어 4만 원", member = "junho")
        assertEquals(listOf(MaintenanceItem.TIRE_ROTATION), h.today().familyUpdates.map { it.item })
        h.cmd(Command.OpenToday(Fx.CAR, "junho"))
        assertTrue(h.last<EngineEvent.TodayReady>().briefing.familyUpdates.isEmpty(), "내가 쓴 기록은 변경 사항이 아니다")
        h.cmd(Command.MarkFeedSeen("minji"))
        h.clock.advance(Duration.ofSeconds(1))
        assertTrue(h.today().familyUpdates.isEmpty(), "확인한 뒤에는 다시 나오지 않는다")
        h.record("와이퍼 갈았어", member = "junho")
        assertEquals(listOf(MaintenanceItem.WIPER), h.today().familyUpdates.map { it.item })
    }

    @Test fun exportOnlyWhatWasChosen() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0))
        h.record("엔진오일 갈았어 8만 원")
        val memo = h.record("브레이크 밟을 때 끼익 소리 나")
        h.cmd(Command.ManualDraft(Fx.CAR, "junho", EntryType.MAINTENANCE, MaintenanceItem.TIRE_ROTATION, performedOn = LocalDate.of(2026, 6, 1)))
        val rot = h.last<EngineEvent.DraftReady>().entry.id
        h.cmd(Command.EditEntry(rot, "junho", EntryEdit(odometerKm = 40_000.0)))
        h.cmd(Command.ConfirmEntry(rot, "junho"))

        val spec = ExportSpec(Fx.CAR, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        h.cmd(Command.Export(spec))
        val r = h.last<EngineEvent.ReportReady>().report
        assertEquals(listOf("타이어 위치 교환", "엔진오일"), r.rows.map { it.category })
        assertTrue(r.rows.all { it.author == null }, "구성원 이름은 기본 제외"); assertNull(r.vin, "VIN은 기본 제외")
        assertEquals(80_000L, r.ledger.total()); assertEquals(1, r.ledger.missingCostCount)
        val text = ReportExporter.render(r)
        assertTrue(text.contains("47,200km(차량 수신)") && text.contains("40,000km(사용자 입력)"), text)
        assertTrue(text.contains("정비소 인증 이력이 아닙니다"))

        h.cmd(Command.Export(spec.copy(memoIds = setOf(memo.id), includeAuthors = true, includeVin = true, items = setOf(MaintenanceItem.ENGINE_OIL))))
        val r2 = h.last<EngineEvent.ReportReady>().report
        assertEquals(listOf("엔진오일", "증상 메모"), r2.rows.map { it.category }, "고른 메모만 함께 나간다")
        assertEquals("민지", r2.rows.first().author); assertEquals("KMHTEST0000000001", r2.vin)
        h.cmd(Command.Export(spec.copy(from = LocalDate.of(2027, 1, 1))))
        assertEquals(RejectReason.INVALID_VALUE, h.last<EngineEvent.Rejected>().reason)
    }
}
