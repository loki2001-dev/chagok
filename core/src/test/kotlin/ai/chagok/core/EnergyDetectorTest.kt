package ai.chagok.core

import ai.chagok.core.detect.EnergyAnchor
import ai.chagok.core.detect.EnergyDetector
import ai.chagok.core.detect.Rise
import ai.chagok.core.model.Observation
import ai.chagok.core.model.VehicleSignal
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 기본 기준: 30초 동안 ±1%p 안에 머물면 안정, 연료 +10%p·SoC +5%p 이상이면 상승, 10분 넘게 조용하면 관측 공백. */
class EnergyDetectorTest {
    private var t: Instant = Fx.T0

    private fun feed(
        d: EnergyDetector, signal: VehicleSignal, values: List<Double?>, odo: (Int) -> Double? = { 1000.0 },
        parked: Boolean? = true, step: Duration = Duration.ofMinutes(1),
    ): List<Rise> = values.mapIndexedNotNull { i, v -> t = t.plus(step); d.onObservation(Observation(Fx.CAR, signal, v, t), odo(i), parked) }

    private fun fuel(d: EnergyDetector, vararg v: Double?, odo: Double? = 1000.0, parked: Boolean? = true) =
        feed(d, VehicleSignal.FUEL_PERCENT, v.toList(), { odo }, parked)

    private fun anchor(d: EnergyDetector, s: VehicleSignal = VehicleSignal.FUEL_PERCENT) = d.anchor(Fx.CAR, s)?.level

    @Test fun firstStableValueAfterStartIsOnlyAnAnchor() {
        val d = EnergyDetector()
        assertTrue(fuel(d, 40.0, 40.0).isEmpty(), "앱 시작 때 이미 있던 잔량을 방금 일어난 일로 쓰지 않는다")
        assertEquals(40.0, anchor(d))
    }

    @Test fun refuelWhileParkedAfterValuesSettle() {
        val d = EnergyDetector()
        fuel(d, 40.0, 40.0)
        val r = fuel(d, 55.0, 70.0, 85.0, 90.0, 90.0).single()
        assertEquals(40.0, r.before.level); assertEquals(90.0, r.afterLevel); assertTrue(r.continuous)
        assertEquals(t, r.afterAt, "값이 30초 넘게 머문 뒤에 후보를 만든다")
    }

    @Test fun changeOnlyListenerSettlesOnTick() {
        // Pleos 잔량 리스너는 값이 바뀔 때만 온다: 주유가 끝난 뒤 96%가 한 번 오고 조용하다
        val d = EnergyDetector()
        d.onObservation(Observation(Fx.CAR, VehicleSignal.FUEL_PERCENT, 40.0, Fx.T0), 1000.0, true)
        assertTrue(d.onTick(Fx.T0.plusSeconds(40), { 1000.0 }, true).isEmpty()); assertEquals(40.0, anchor(d))
        val at = Fx.T0.plusSeconds(300)
        assertNull(d.onObservation(Observation(Fx.CAR, VehicleSignal.FUEL_PERCENT, 96.0, at), 1000.0, true))
        assertTrue(d.onTick(at.plusSeconds(10), { 1000.0 }, true).isEmpty(), "30초가 지나기 전에는 판단하지 않는다")
        val r = d.onTick(at.plusSeconds(40), { 1000.0 }, true).single()
        assertEquals(96.0, r.afterLevel); assertEquals(at, r.afterAt, "후보 시각은 값을 받은 시각"); assertTrue(r.continuous)
    }

    @Test fun ticksKeepTheObservationContinuous() {
        val d = EnergyDetector()
        d.onObservation(Observation(Fx.CAR, VehicleSignal.FUEL_PERCENT, 40.0, Fx.T0), 1000.0, true)
        var now = Fx.T0
        repeat(12) { now = now.plus(Duration.ofMinutes(5)); d.onTick(now, { 1000.0 }, true) }   // 1시간 동안 값은 그대로, 앱은 살아 있다
        d.onObservation(Observation(Fx.CAR, VehicleSignal.FUEL_PERCENT, 90.0, now.plusSeconds(1)), 1000.0, true)
        assertTrue(d.onTick(now.plusSeconds(60), { 1000.0 }, true).single().continuous, "값이 오래 그대로인 것은 관측 공백이 아니다")
    }

    @Test fun consumptionWhileDrivingNeverRises() {
        val d = EnergyDetector()
        var km = 1000.0
        val r = feed(d, VehicleSignal.FUEL_PERCENT, listOf(60.0, 59.8, 59.6, 59.4, 59.2, 59.0, 58.8), { km += 1.0; km }, parked = false)
        assertTrue(r.isEmpty())
        assertEquals(59.0, anchor(d), "58.8은 방금 새 대역에 들어온 값이라 30초가 지나야 기준이 된다")
        d.onTick(t.plusSeconds(40), { 1007.0 }, false)
        assertEquals(58.8, anchor(d))
    }

    @Test fun platformSaysMovingSoSocRiseIsNotCharging() {
        val d = EnergyDetector()
        val socs = listOf(60.0, 60.0, 70.0, 70.0)
        assertTrue(feed(d, VehicleSignal.EV_SOC_PERCENT, socs, { 2000.0 }, parked = false).isEmpty(), "주행 중 상승(회생제동·보정)은 충전이 아니다")
    }

    @Test fun parkedHintWorksWithoutOdometer() {
        val d = EnergyDetector()
        fuel(d, 40.0, 40.0, odo = null, parked = true)
        assertEquals(90.0, fuel(d, 90.0, 90.0, odo = null, parked = true).single().afterLevel, "주행거리가 없어도 플랫폼이 정차라고 하면 된다")
        val unknown = EnergyDetector()
        fuel(unknown, 40.0, 40.0, odo = null, parked = null)
        assertTrue(fuel(unknown, 90.0, 90.0, odo = null, parked = null).isEmpty(), "정차를 전혀 확인할 수 없으면 연속 관측 후보를 만들지 않는다")
    }

    @Test fun slowChargingAccumulatesWhileParked() {
        val d = EnergyDetector()
        val socs = listOf(50.0, 50.0) + (1..12).map { 50.0 + it * 0.5 }
        val r = feed(d, VehicleSignal.EV_SOC_PERCENT, socs, { 2000.0 }, step = Duration.ofMinutes(5)).first()
        assertEquals(50.0, r.before.level); assertTrue(r.afterLevel >= 55.0, "조금씩 오르면 기준을 올리지 않고 누적한다: ${r.afterLevel}")
    }

    @Test fun smallParkedRiseBelowThresholdIsIgnored() {
        val d = EnergyDetector()
        fuel(d, 40.0, 40.0)
        assertTrue(fuel(d, 45.0, 45.0).isEmpty(), "경사·센서 보정 수준의 상승은 후보가 아니다")
        assertEquals(40.0, anchor(d))
    }

    @Test fun riseAfterSilenceIsNonContinuousEvenIfTheCarMoved() {
        val d = EnergyDetector()
        fuel(d, 40.0, 40.0, odo = 1000.0)
        t = t.plus(Duration.ofHours(2))
        assertTrue(!fuel(d, 80.0, 80.0, odo = 1005.0).single().continuous, "앱이 꺼져 있던 사이 상승은 '마지막 관측 이후 잔량 증가'")
    }

    @Test fun missingValueOrLostSourceBreaksContinuity() {
        val d = EnergyDetector()
        fuel(d, 40.0, 40.0)
        assertTrue(!fuel(d, null, 80.0, 80.0).single().continuous)
        val g = EnergyDetector()
        fuel(g, 40.0, 40.0)
        g.markGap(VehicleSignal.FUEL_PERCENT)
        assertTrue(!fuel(g, 80.0, 80.0).single().continuous, "수신이 끊겼던 동안의 변화")
    }

    @Test fun restoredAnchorAfterAppRestart() {
        val d = EnergyDetector()
        d.restore(EnergyAnchor(Fx.CAR, VehicleSignal.FUEL_PERCENT, 35.0, Fx.T0, 1000.0))
        t = Fx.T0.plus(Duration.ofHours(1))
        val r = assertNotNull(fuel(d, 92.0, 92.0).singleOrNull())
        assertEquals(35.0, r.before.level); assertTrue(!r.continuous)
    }

    @Test fun outOfRangeValuesAreIgnored() {
        val d = EnergyDetector()
        fuel(d, 40.0, 40.0)
        assertTrue(fuel(d, 140.0, -1.0, Double.NaN).isEmpty())
        assertNull(d.onObservation(Observation(Fx.CAR, VehicleSignal.ODOMETER_KM, 5.0, t), 5.0, true))
    }
}
