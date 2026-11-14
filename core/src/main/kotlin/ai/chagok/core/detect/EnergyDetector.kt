package ai.chagok.core.detect

import ai.chagok.core.diary.EntryType
import ai.chagok.core.model.Observation
import ai.chagok.core.model.VehicleSignal
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

enum class CandidateStatus { PENDING, CONFIRMED, DISMISSED }

/**
 * 주유·충전 기록 후보. 확정 이벤트나 결제 데이터가 아니다: 사용자 확인 전에는 비용 합계와 일지에 반영하지 않고,
 * 주유량(L)·충전량(kWh)·금액·장소를 %에서 추정하지 않는다.
 * continuous=false면 그 사이를 관측하지 못했다는 뜻이라 "마지막 관측 이후 잔량 증가"로만 설명한다.
 */
data class EnergyCandidate(
    val id: String,
    val vehicleId: String,
    val type: EntryType,
    val beforePercent: Double,
    val beforeAt: Instant,
    val afterPercent: Double,
    val afterAt: Instant,
    val continuous: Boolean,
    val createdAt: Instant,
    val status: CandidateStatus = CandidateStatus.PENDING,
    val linkedEntryId: String? = null,
) {
    val risePercent: Double get() = afterPercent - beforePercent
    val description: String
        get() {
            val span = "${fmt(beforePercent)}% → ${fmt(afterPercent)}%"
            return if (continuous) "잔량 상승 관측 $span" else "마지막 관측 이후 잔량 증가 $span"
        }
}

/** 신호별 마지막 안정 잔량. 저장해 두면 앱을 다시 켰을 때 그 사이 증가를 후보로 물을 수 있다. */
data class EnergyAnchor(val vehicleId: String, val signal: VehicleSignal, val level: Double, val at: Instant, val odometerKm: Double?)

/** 감지된 상승. 후보로 만들지·기존 후보에 합칠지는 Engine이 정한다. */
data class Rise(val before: EnergyAnchor, val afterLevel: Double, val afterAt: Instant, val continuous: Boolean)

/**
 * 감지 기준. 전부 초안값이며 실측 후 조정한다.
 * stableFor: 값이 stableTolerancePct 안에서 이만큼 유지되면 "안정됐다"고 본다(주유 중의 흔들림·경사 보정 배제).
 *   Pleos 잔량 리스너는 값이 바뀔 때만 오므로, 안정 판정은 다음 값이 아니라 시간으로 한다(Engine.onTick).
 * maxSilence: 값도 tick도 없이 이만큼 지나면 앱이 그 사이를 관측하지 못한 것으로 본다(앱 종료·서비스 끊김).
 * parkedOdometerToleranceKm: 기준 시점 이후 주행거리가 이만큼 넘게 늘었으면 주행 중 변화로 본다.
 */
data class DetectionConfig(
    val minFuelRisePct: Double = 10.0,
    val minSocRisePct: Double = 5.0,
    val stableFor: Duration = Duration.ofSeconds(30),
    val stableTolerancePct: Double = 1.0,
    val maxSilence: Duration = Duration.ofMinutes(10),
    val parkedOdometerToleranceKm: Double = 0.3,
)

/**
 * 연료 잔량·SoC 관측에서 주유·충전 후보를 찾는다.
 * - 앱을 켜고 처음 안정된 값은 기준만 잡는다(앱 시작 때 이미 있던 값을 "방금 일어난 일"로 쓰지 않는다).
 * - 기준은 내려가거나(소비) 차가 움직였을 때만 옮긴다. 정차 중 작은 상승은 누적해서 완속·급속 충전처럼 서서히 오르는 경우도 잡는다.
 * - 정차 여부는 플랫폼 주행 상태(parked)를 먼저 보고, 모르면 주행거리 변화로 판단한다. 둘 다 모르면 연속 관측 후보를 만들지 않는다.
 * - 관측 공백(미수신·앱이 꺼져 있던 구간·서비스 끊김) 뒤 상승은 continuous=false 후보로만 만든다.
 */
class EnergyDetector(private val cfg: DetectionConfig = DetectionConfig()) {
    private data class Reading(val level: Double, val at: Instant)
    private class Track(var anchor: EnergyAnchor?, var alive: Instant?, var gap: Boolean) {
        var settle: Reading? = null   // 지금 값 대역에 처음 들어온 값과 시각
        var last: Reading? = null
    }
    private val tracks = HashMap<Pair<String, VehicleSignal>, Track>()

    fun hasTrack(vehicleId: String, signal: VehicleSignal) = tracks.containsKey(vehicleId to signal)
    fun anchor(vehicleId: String, signal: VehicleSignal): EnergyAnchor? = tracks[vehicleId to signal]?.anchor
    fun anchors(): List<EnergyAnchor> = tracks.values.mapNotNull { it.anchor }

    /** 저장된 기준으로 시작한다. 다음 관측까지의 시간이 maxSilence를 넘으면 그 사이는 관측하지 못한 구간이 된다. */
    fun restore(a: EnergyAnchor) { tracks[a.vehicleId to a.signal] = Track(a, a.at, false) }

    /** 수신이 끊겼다(Producer 상태가 AVAILABLE이 아님). 그 사이 변화는 연속 관측이 아니다. */
    fun markGap(signal: VehicleSignal) {
        tracks.filterKeys { it.second == signal }.values.forEach { it.gap = true; it.settle = null; it.last = null }
    }

    fun onObservation(o: Observation, odometerKm: Double?, parked: Boolean?): Rise? {
        if (o.signal == VehicleSignal.ODOMETER_KM) return null
        val t = tracks.getOrPut(o.vehicleId to o.signal) { Track(null, null, false) }
        touch(t, o.receivedAt)
        val v = o.value
        if (v == null || v.isNaN() || v < 0.0 || v > 100.0) { t.gap = true; t.settle = null; t.last = null; return null }
        val r = Reading(v, o.receivedAt)
        val s = t.settle
        if (s == null || abs(v - s.level) > cfg.stableTolerancePct) t.settle = r
        t.last = r
        return evaluate(o.vehicleId, o.signal, t, o.receivedAt, odometerKm, parked)
    }

    /** 시간이 흘렀다. 값이 바뀌지 않아 리스너가 조용해도 안정 판정을 진행한다. 앱이 살아 있다는 신호이기도 하다. */
    fun onTick(now: Instant, odometerKm: (String) -> Double?, parked: Boolean?): List<Rise> =
        tracks.mapNotNull { (k, t) -> touch(t, now); evaluate(k.first, k.second, t, now, odometerKm(k.first), parked) }

    private fun touch(t: Track, at: Instant) {
        val prev = t.alive
        // 오래 조용했다: 그 사이는 관측하지 못했다. 공백 전 값으로 안정을 판정하지 않고 새 값을 기다린다(앱은 재개 때 한 번 get 한다)
        if (prev != null && Duration.between(prev, at) > cfg.maxSilence) { t.gap = true; t.settle = null; t.last = null }
        if (prev == null || at.isAfter(prev)) t.alive = at
    }

    private fun evaluate(vehicleId: String, signal: VehicleSignal, t: Track, now: Instant, odometerKm: Double?, parked: Boolean?): Rise? {
        val s = t.settle ?: return null
        val last = t.last ?: return null
        if (Duration.between(s.at, now) < cfg.stableFor) return null
        val level = last.level
        val here = EnergyAnchor(vehicleId, signal, level, last.at, odometerKm)
        val a = t.anchor ?: run { t.anchor = here; t.gap = false; return null }
        val threshold = if (signal == VehicleSignal.FUEL_PERCENT) cfg.minFuelRisePct else cfg.minSocRisePct
        val odoKnown = odometerKm != null && a.odometerKm != null
        val odoMoved = odoKnown && odometerKm!! - a.odometerKm!! > cfg.parkedOdometerToleranceKm
        val moved = parked == false || odoMoved
        val parkedConfirmed = !moved && (parked == true || odoKnown)
        val rise = level - a.level
        return when {
            rise >= threshold && (t.gap || parkedConfirmed) -> {
                val r = Rise(a, level, last.at, continuous = !t.gap)
                t.anchor = here; t.gap = false; r
            }
            level <= a.level || moved || rise >= threshold -> { t.anchor = here; t.gap = false; null }
            else -> null
        }
    }
}

internal fun fmt(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else String.format("%.1f", v)
