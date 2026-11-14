package io.chagok.app.domain

import java.time.Duration
import java.time.Instant

/** Persist the session anchor separately from the most recent stable observation. */
data class EnergyCheckpoint(val baseline: Observation?, val anchor: Observation?, val lastIncrease: Instant?,
    val lastRawAt: Instant?, val gapLong: Boolean)

class EnergyTracker(
    val kind: EnergyKind,
    private val risePoints: Double = 5.0,
    private val stableFor: Duration = Duration.ofSeconds(10),
    private val stableTolerance: Double = 1.0,
    private val gapLimit: Duration = Duration.ofMinutes(2),
) {
    var baseline: Observation? = null; private set
    private var anchor: Observation? = null
    private var lastIncrease: Instant? = null
    private var settling: Observation? = null
    private var lastRawAt: Instant? = null
    private var gapLong=false
    fun restore(value: Observation?) { baseline=value;anchor=value;gapLong=value!=null }
    fun restoreCheckpoint(value: EnergyCheckpoint) {
        baseline=value.baseline;anchor=value.anchor;lastIncrease=value.lastIncrease;lastRawAt=value.lastRawAt;gapLong=value.gapLong
    }
    fun checkpoint()=EnergyCheckpoint(baseline,anchor,lastIncrease,lastRawAt,gapLong)
    sealed interface Result {
        data object None : Result
        data class BaselineMoved(val baseline: Observation) : Result
        data class Rise(val before: Observation,val after: Observation,val gapLong: Boolean,val finished: Boolean=false) : Result
    }
    private fun finish(): Result {
        val a=anchor;val b=baseline;val gap=gapLong
        anchor=null;lastIncrease=null;gapLong=false
        return if(a!=null&&b!=null&&b.value-a.value>=risePoints)Result.Rise(a,b,gap,true)else Result.None
    }
    fun feed(value: Double?, at: Instant, parked: Boolean): Result {
        if(value==null||!value.isFinite()||value !in 0.0..100.0) { settling=null;gapLong=true;return Result.None }
        if(lastRawAt==null||Duration.between(lastRawAt,at)>gapLimit||at<lastRawAt)gapLong=true
        lastRawAt=at
        if(!parked) { settling=null;return finish() }
        val s=settling
        if(s==null||kotlin.math.abs(s.value-value)>stableTolerance) { settling=Observation(value,at);return Result.None }
        if(Duration.between(s.receivedAt,at)<stableFor)return Result.None
        settling=null
        val stable=Observation(value,at)
        val before=baseline
        if(before==null) { baseline=stable;anchor=stable;gapLong=false;return Result.BaselineMoved(stable) }
        if(anchor==null)anchor=before
        if(stable.value<before.value-stableTolerance) {
            val finished=finish();baseline=stable;anchor=stable
            return if(finished is Result.Rise)finished else Result.BaselineMoved(stable)
        }
        if(stable.value>before.value+0.05)lastIncrease=at
        baseline=stable
        val a=anchor!!
        if(stable.value<a.value) { anchor=stable;return Result.BaselineMoved(stable) }
        if(stable.value-a.value>=risePoints) {
            // A stopped rise is provisional: no amount, kWh or actual charging signal is inferred.
            val quietSeconds=if(kind==EnergyKind.SOC)1800L else 60L
            if(lastIncrease!=null&&Duration.between(lastIncrease,at).seconds>=quietSeconds)return finish()
            return Result.Rise(a,stable,gapLong)
        }
        return Result.BaselineMoved(stable)
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
