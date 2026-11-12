package io.chagok.app.domain

import java.time.Duration
import java.time.Instant

/**
 * 연료 잔량·SoC 관측을 받아 주유·충전 후보를 만든다 (기획서 5장 '주유 후보', '충전 후보').
 *
 * - 정차 중에 값이 안정된 경우에만 비교한다. 같은 값이 [stableFor] 이상 유지되면 안정으로 본다.
 * - 직전 안정 관측보다 [risePoints]%p 이상 오르면 후보를 만든다. 감지 기준은 실측 후 조정한다.
 * - 관측 사이 공백이 [gapLimit]보다 길면 '마지막 관측 이후 잔량 증가'로만 설명한다.
 * - 후보는 사용자가 확인하기 전까지 일지·비용·정비 완료에 들어가지 않는다.
 * - L·kWh·금액·장소를 %에서 추정하지 않는다.
 */
class EnergyTracker(
    val kind: EnergyKind,
    private val risePoints: Double = 5.0,
    private val stableFor: Duration = Duration.ofSeconds(10),
    private val stableTolerance: Double = 1.0,
    private val gapLimit: Duration = Duration.ofMinutes(2),
) {
    /** 마지막으로 안정된 관측. 비교의 기준선이며 앱 재시작에도 유지해야 한다 */
    var baseline: Observation? = null
        private set

    private var settling: Observation? = null
    private var lastRawAt: Instant? = null
    private var gapSinceBaseline = false

    fun restore(baseline: Observation?) {
        this.baseline = baseline
        // 저장된 기준선 이후는 관측하지 못한 구간이다
        gapSinceBaseline = baseline != null
    }

    sealed interface Result {
        data object None : Result

        /** 기준선이 바뀌었다. 저장소에 남겨야 한다 */
        data class BaselineMoved(val baseline: Observation) : Result

        data class Rise(val before: Observation, val after: Observation, val gapLong: Boolean) : Result
    }

    /**
     * @param value null이면 미수신. 0으로 채우지 않는다.
     * @param parked 정차 여부. 주행 중 값은 안정 판정에 쓰지 않는다.
     */
    fun feed(value: Double?, at: Instant, parked: Boolean): Result {
        val previousRawAt = lastRawAt
        if (value == null) return Result.None
        lastRawAt = at
        if (previousRawAt == null || Duration.between(previousRawAt, at) > gapLimit) gapSinceBaseline = true

        if (!parked) {
            settling = null
            return Result.None
        }
        val s = settling
        if (s == null || kotlin.math.abs(s.value - value) > stableTolerance) {
            settling = Observation(value, at)
            return Result.None
        }
        if (Duration.between(s.receivedAt, at) < stableFor) return Result.None

        val stable = Observation(value, at)
        settling = null
        val before = baseline
        baseline = stable
        val gap = gapSinceBaseline
        gapSinceBaseline = false
        return if (before != null && stable.value - before.value >= risePoints) {
            Result.Rise(before, stable, gap)
        } else {
            Result.BaselineMoved(stable)
        }
    }
}

/** 후보 중복 방지: 같은 원인(겹치는 관측 구간)에서 후보를 두 번 만들지 않는다 */
object CandidateRules {
    fun isDuplicate(rise: EnergyTracker.Result.Rise, kind: EnergyKind, existing: List<DetectionCandidate>): Boolean =
        existing.any {
            it.kind == kind &&
                it.before.receivedAt < rise.after.receivedAt &&
                rise.before.receivedAt < it.after.receivedAt
        }

    fun describe(c: DetectionCandidate): String {
        val what = if (c.kind == EnergyKind.FUEL) "주유" else "충전"
        val change = "%.0f%% → %.0f%%".format(c.before.value, c.after.value)
        return if (c.gapLong) {
            "마지막 관측 이후 ${c.kind.label} 증가 ($change). ${what}했나요?"
        } else {
            "정차 중 ${c.kind.label} 상승 ($change). ${what}했나요?"
        }
    }
}
