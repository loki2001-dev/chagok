package ai.chagok.core

import ai.chagok.core.detect.CandidateStatus
import ai.chagok.core.diary.EntryStatus
import ai.chagok.core.diary.EntryType
import ai.chagok.core.ledger.CostLedger
import ai.chagok.core.model.Powertrain
import ai.chagok.core.share.CheckItem
import ai.chagok.core.store.InMemoryRepository
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandidateFlowTest {
    private var t: Instant = Fx.T0.minus(Duration.ofMinutes(30))

    /** 정차(주행거리 고정) 상태에서 잔량을 차례로 넣는다. */
    private fun Harness.levels(vararg v: Double, soc: Boolean = false, km: Double = 47_200.0) {
        for (x in v) {
            t = t.plus(Duration.ofMinutes(1))
            obs(Fx.odo(km, t)); obs(if (soc) Fx.soc(x, t) else Fx.fuel(x, t))
        }
    }

    @Test fun refuelCandidateLinkedByVoiceThenConfirmed() = runTest {
        val h = Harness(this)
        h.levels(30.0, 30.0, 30.0, 60.0, 80.0, 95.0, 95.0, 95.0)
        val c = h.last<EngineEvent.CandidateCreated>().candidate
        assertEquals(EntryType.FUEL, c.type); assertEquals(30.0, c.beforePercent); assertEquals(95.0, c.afterPercent); assertTrue(c.continuous)
        assertTrue(c.description.startsWith("잔량 상승 관측"))
        assertEquals(0L, CostLedger.summarize(h.repo.entries(), Fx.CAR).total(), "후보는 비용에 들어가지 않는다")

        h.say("주유했어. 5만 원")
        val d = h.last<EngineEvent.DraftReady>()
        assertEquals(c.id, d.linkedCandidate?.id); assertEquals(c.id, d.entry.candidateId)
        h.cmd(Command.ConfirmEntry(d.entry.id, "minji"))
        val r = h.last<EngineEvent.CandidateResolved>().candidate
        assertEquals(CandidateStatus.CONFIRMED, r.status); assertEquals(d.entry.id, r.linkedEntryId)
        assertEquals(50_000L, CostLedger.summarize(h.repo.entries(), Fx.CAR).total())

        h.cmd(Command.CancelEntry(d.entry.id, "minji"))
        assertEquals(CandidateStatus.PENDING, h.repo.candidate(c.id)!!.status, "기록을 취소하면 후보는 다시 확인 대기")
    }

    @Test fun confirmCandidateDirectlyWithoutAmount() = runTest {
        val h = Harness(this)
        h.levels(30.0, 30.0, 30.0, 95.0, 95.0, 95.0)
        val c = h.last<EngineEvent.CandidateCreated>().candidate
        h.cmd(Command.ConfirmCandidate(c.id, "junho"))
        val e = h.last<EngineEvent.EntryConfirmed>().entry
        assertEquals(EntryType.FUEL, e.type); assertEquals(null, e.cost, "금액·주유량을 추정하지 않는다"); assertEquals("candidate:${c.id}", e.sourceKey)
        assertEquals(1, CostLedger.summarize(h.repo.entries(), Fx.CAR).missingCostCount)
        h.cmd(Command.ConfirmCandidate(c.id, "junho"))
        assertEquals(RejectReason.INVALID_STATE, h.last<EngineEvent.Rejected>().reason)
        assertEquals(1, h.repo.entries().size)
    }

    @Test fun dismissedCandidateLeavesNoEntry() = runTest {
        val h = Harness(this)
        h.levels(30.0, 30.0, 30.0, 95.0, 95.0, 95.0)
        val c = h.last<EngineEvent.CandidateCreated>().candidate
        h.cmd(Command.OpenToday(Fx.CAR, "minji"))
        assertTrue(h.last<EngineEvent.TodayReady>().briefing.toCheck.any { it is CheckItem.PendingCandidate && it.candidate.id == c.id })
        h.cmd(Command.DismissCandidate(c.id, "minji"))
        assertEquals(CandidateStatus.DISMISSED, h.last<EngineEvent.CandidateResolved>().candidate.status)
        assertTrue(h.repo.entries().isEmpty())
    }

    @Test fun stagedRefuelMergesIntoOneCandidate() = runTest {
        val h = Harness(this)
        h.levels(30.0, 30.0, 30.0, 60.0, 60.0, 60.0, 90.0, 90.0, 90.0)
        assertEquals(1, h.all<EngineEvent.CandidateCreated>().size)
        val up = h.last<EngineEvent.CandidateUpdated>().candidate
        assertEquals(30.0, up.beforePercent); assertEquals(90.0, up.afterPercent)
    }

    @Test fun chargeOnlyForPlugInVehicles() = runTest {
        val ev = Harness(this, Powertrain.EV)
        ev.levels(40.0, 40.0, 40.0, 80.0, 80.0, 80.0, soc = true)
        assertEquals(EntryType.CHARGE, ev.last<EngineEvent.CandidateCreated>().candidate.type)
        val hev = Harness(this, Powertrain.HEV, repo = InMemoryRepository())
        hev.levels(40.0, 40.0, 40.0, 80.0, 80.0, 80.0, soc = true)
        assertTrue(hev.all<EngineEvent.CandidateCreated>().isEmpty(), "하이브리드의 SoC 상승은 외부 충전이 아니다")
    }

    @Test fun riseWhileAppWasOffIsDescribedAsSinceLastObservation() = runTest {
        val repo = InMemoryRepository()
        val first = Harness(this, repo = repo)
        first.levels(35.0, 35.0, 35.0)
        // 앱 재시작: 같은 저장소, 새 엔진. 2시간 뒤 다시 연결됐을 때 잔량이 올라 있다
        val later = Harness(this, repo = repo, clock = TestClock(t.plus(Duration.ofHours(2))))
        t = t.plus(Duration.ofHours(2))
        later.levels(92.0, 92.0, 92.0, km = 47_230.0)
        val c = later.last<EngineEvent.CandidateCreated>().candidate
        assertTrue(!c.continuous); assertTrue(c.description.startsWith("마지막 관측 이후 잔량 증가"))
        assertEquals(35.0, c.beforePercent)
    }

    @Test fun gradualChargeWithChangeOnlyListener() = runTest {
        // 충전 중 SoC가 2분마다 1%씩 오르고, 리스너는 값이 바뀔 때만 온다. 앱은 5초마다 tick을 보낸다
        val h = Harness(this, Powertrain.EV)
        fun at(soc: Double) { h.obs(Fx.odo(12_000.0, h.clock.now)); h.obs(Fx.soc(soc, h.clock.now)) }
        at(40.0); h.wait(Duration.ofMinutes(1))
        for (s in 41..50) { at(s.toDouble()); h.wait(Duration.ofMinutes(2)) }
        assertEquals(1, h.all<EngineEvent.CandidateCreated>().size, "서서히 오르는 충전도 한 후보로 잡는다")
        val c = h.repo.candidates().single()
        assertEquals(EntryType.CHARGE, c.type); assertEquals(40.0, c.beforePercent); assertEquals(50.0, c.afterPercent); assertTrue(c.continuous)
    }

    @Test fun noCandidateWhilePlatformSaysDriving() = runTest {
        val h = Harness(this)
        h.driving = true
        h.levels(30.0, 30.0, 30.0, 95.0, 95.0, 95.0)
        assertTrue(h.all<EngineEvent.CandidateCreated>().isEmpty(), "주행 제한 중(움직이는 중)의 상승은 주유가 아니다")
    }

    @Test fun pendingCandidatesNeverCompleteMaintenance() = runTest {
        val h = Harness(this)
        h.levels(30.0, 30.0, 30.0, 95.0, 95.0, 95.0)
        assertTrue(h.repo.entries().none { it.status == EntryStatus.CONFIRMED })
    }
}
