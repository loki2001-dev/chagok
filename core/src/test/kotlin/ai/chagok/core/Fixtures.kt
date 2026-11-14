package ai.chagok.core

import ai.chagok.core.diary.DiaryEntry
import ai.chagok.core.model.Member
import ai.chagok.core.model.Observation
import ai.chagok.core.model.Powertrain
import ai.chagok.core.model.VehicleProfile
import ai.chagok.core.model.VehicleSignal
import ai.chagok.core.store.InMemoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = Fx.ZONE
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

object Fx {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    /** 기획서 시연일 2026.11.08 오전 10시. */
    val T0: Instant = ZonedDateTime.of(2026, 11, 8, 10, 0, 0, 0, ZONE).toInstant()
    val TODAY: LocalDate = LocalDate.of(2026, 11, 8)
    const val CAR = "car"

    fun odo(km: Double?, at: Instant, car: String = CAR) = Observation(car, VehicleSignal.ODOMETER_KM, km, at)
    fun fuel(pct: Double?, at: Instant, car: String = CAR) = Observation(car, VehicleSignal.FUEL_PERCENT, pct, at)
    fun soc(pct: Double?, at: Instant, car: String = CAR) = Observation(car, VehicleSignal.EV_SOC_PERCENT, pct, at)
}

/** Engine을 동기적으로 몰고 이벤트를 모은다. 수집기는 backgroundScope라 테스트가 끝나면 자동으로 취소된다. */
@OptIn(ExperimentalCoroutinesApi::class)
class Harness(
    val scope: TestScope,
    powertrain: Powertrain = Powertrain.ICE,
    val repo: InMemoryRepository = InMemoryRepository(),
    val clock: TestClock = TestClock(Fx.T0),
) {
    var driving = false
    /** false면 차량 서비스에 연결하지 못해 주행 상태를 모르는 경우. */
    var gateKnown = true
    val engine = Engine(repo, clock, Fx.ZONE, object : DrivingGate {
        override fun restricted() = driving
        override fun known() = gateKnown
    })
    val events = ArrayList<EngineEvent>()
    private var n = 0

    init {
        if (repo.vehicle(Fx.CAR) == null) repo.saveVehicle(VehicleProfile(Fx.CAR, "우리 차", powertrain, "아반떼", vin = "KMHTEST0000000001"))
        repo.saveMember(Member("minji", "민지")); repo.saveMember(Member("junho", "준호"))
        // advanceUntilIdle은 backgroundScope 작업을 돌리지 않으므로 수집기는 즉시 실행되는 디스패처로 띄운다
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { engine.events.collect { events.add(it) } }
        scope.advanceUntilIdle()
    }

    fun cmd(c: Command) { engine.onCommand(c); scope.advanceUntilIdle() }
    fun obs(o: Observation) { engine.onObservation(o); scope.advanceUntilIdle() }
    fun clear() = events.clear()

    /** 앱 타이머처럼 5초마다 시계를 움직이고 tick을 보낸다. */
    fun wait(d: Duration) {
        var left = d
        while (!left.isNegative && !left.isZero) {
            val step = if (left < Duration.ofSeconds(5)) left else Duration.ofSeconds(5)
            clock.advance(step); engine.onTick(); left = left.minus(step)
        }
        scope.advanceUntilIdle()
    }

    /** 음성 한 번: 세션 시작 → 최종 텍스트. 세션 id를 돌려준다. */
    fun say(text: String, member: String = "minji"): String {
        val s = "s${++n}"
        cmd(Command.VoiceStarted(s, Fx.CAR, member)); cmd(Command.VoiceFinal(s, text)); return s
    }

    /** 말하고 바로 확정. */
    fun record(text: String, member: String = "minji"): DiaryEntry {
        say(text, member)
        val d = last<EngineEvent.DraftReady>().entry
        cmd(Command.ConfirmEntry(d.id, member, acceptApproximateDate = true))
        return repo.entry(d.id)!!
    }

    inline fun <reified T : EngineEvent> all(): List<T> = events.filterIsInstance<T>()
    inline fun <reified T : EngineEvent> last(): T = all<T>().last()
}
