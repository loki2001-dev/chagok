package ai.chagok.core

import ai.chagok.core.detect.CandidateStatus
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.ledger.CostLedger
import ai.chagok.core.model.EvidenceSource
import ai.chagok.core.model.Member
import ai.chagok.core.model.Powertrain
import ai.chagok.core.model.SourceStatus
import ai.chagok.core.model.VehicleProfile
import ai.chagok.core.model.VehicleSignal
import ai.chagok.core.pipeline.CommandBus
import ai.chagok.core.pipeline.ObservationBus
import ai.chagok.core.sim.VehicleReplay
import ai.chagok.core.store.InMemoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 스모크: 실제 배선으로 한 바퀴. 리플레이 fixture → ObservationBus → Engine.start → CommandBus 명령 → events.
 * 엔진 메서드를 직접 부르지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmokeTest {
    @Test fun refuelStopThenOilChangeEndToEnd() = runTest {
        val repo = InMemoryRepository().apply {
            saveVehicle(VehicleProfile(Fx.CAR, "우리 차", Powertrain.ICE)); saveMember(Member("minji", "민지"))
        }
        val clock = TestClock(Fx.T0.plus(Duration.ofMinutes(10)))
        val engine = Engine(repo, clock, Fx.ZONE)
        val obs = ObservationBus(); val cmds = CommandBus()
        val seen = ArrayList<EngineEvent>()
        val collector = launch { engine.events.collect { seen.add(it) } }
        val text = checkNotNull(javaClass.getResource("/fixtures/refuel_stop.csv")).readText()
        val replay = VehicleReplay.fromText(text, Fx.CAR, Fx.T0)
        val job = engine.start(this, obs, cmds, listOf(replay))
        replay.start(this, obs)        // 엔진 구독 전에 시작해도 리플레이가 기다린다
        advanceUntilIdle()

        assertEquals(SourceStatus.AVAILABLE, engine.sourceStatus.value[VehicleSignal.FUEL_PERCENT])
        val cand = seen.filterIsInstance<EngineEvent.CandidateCreated>().single().candidate
        assertEquals(31.6, cand.beforePercent); assertEquals(96.0, cand.afterPercent); assertTrue(cand.continuous)

        cmds.send(Command.SetPlan(Fx.CAR, MaintenanceItem.ENGINE_OIL, 10_000.0, 12, "사용자 확인: 차량 매뉴얼"))
        cmds.send(Command.VoiceStarted("v1", Fx.CAR, "minji")); cmds.send(Command.VoiceFinal("v1", "오늘 엔진오일 갈았어. 8만 원"))
        advanceUntilIdle()
        val oil = seen.filterIsInstance<EngineEvent.DraftReady>().last().entry
        assertEquals(47_200.0, oil.odometer!!.value); assertEquals(EvidenceSource.VEHICLE, oil.odometer!!.source)
        assertEquals(Fx.T0.plusMillis(540_000), oil.odometer!!.receivedAt, "미수신(null)을 건너뛴 마지막 유효 값")
        cmds.send(Command.ConfirmEntry(oil.id, "minji"))

        cmds.send(Command.VoiceStarted("v2", Fx.CAR, "minji")); cmds.send(Command.VoiceFinal("v2", "주유했어. 5만 원"))
        advanceUntilIdle()
        val fuel = seen.filterIsInstance<EngineEvent.DraftReady>().last()
        assertEquals(cand.id, fuel.linkedCandidate?.id)
        cmds.send(Command.ConfirmEntry(fuel.entry.id, "minji"))
        advanceUntilIdle()

        assertEquals(CandidateStatus.CONFIRMED, repo.candidate(cand.id)!!.status)
        assertEquals(130_000L, CostLedger.summarize(repo.entries(), Fx.CAR).total())
        val sched = seen.filterIsInstance<EngineEvent.ScheduleChanged>().last().statuses.single()
        assertEquals(57_200.0, sched.nextKm)
        assertTrue(seen.none { it is EngineEvent.Rejected }, seen.filterIsInstance<EngineEvent.Rejected>().toString())
        job.cancel(); collector.cancel(); replay.stop()
    }
}
