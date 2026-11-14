package io.chagok.app.companion

import io.chagok.app.domain.PlaceKind
import io.chagok.app.domain.PlaceVisit
import io.chagok.app.domain.VisitState
import java.time.Instant
import kotlin.math.abs

/** A fictional welcome, never evidence of fuel purchased or work completed. */
class ServiceVisitAnimation {
    enum class Phase { APPROACH, WORK, LEAVE }
    data class Frame(val visitId: String, val kind: PlaceKind, val seconds: Float = 0f) {
        val phase get() = when {
            seconds < APPROACH_SECONDS -> Phase.APPROACH
            seconds < APPROACH_SECONDS + WORK_SECONDS -> Phase.WORK
            else -> Phase.LEAVE
        }
        val approach get() = when (phase) {
            Phase.APPROACH -> seconds / APPROACH_SECONDS
            Phase.WORK -> 1f
            Phase.LEAVE -> 1f - (seconds - APPROACH_SECONDS - WORK_SECONDS) / LEAVE_SECONDS
        }.coerceIn(0f, 1f)
        val caption get() = when (phase) {
            Phase.APPROACH -> if (kind == PlaceKind.FUEL) "주유 도우미가 다가와요" else "정비사가 다가와요"
            Phase.WORK -> if (kind == PlaceKind.FUEL) "차곡차곡, 주유 중" else "뚝딱뚝딱, 점검 중"
            Phase.LEAVE -> "다음 길도 함께해요"
        }
    }

    var frame: Frame? = null; private set
    fun begin(visitId: String, kind: PlaceKind): Boolean {
        if (frame != null) return false
        frame = Frame(visitId, kind)
        return true
    }
    fun cancel() { frame = null }
    fun advance(dt: Float, motion: MotionSnapshot) {
        if (!safe(motion)) { cancel(); return }
        val previous = frame ?: return
        if (!dt.isFinite() || dt <= 0f) return
        val seconds = previous.seconds + dt
        frame = if (seconds >= TOTAL_SECONDS) null else previous.copy(seconds = seconds)
    }

    companion object {
        const val APPROACH_SECONDS = 2.5f
        const val WORK_SECONDS = 8f
        const val LEAVE_SECONDS = 2.5f
        const val TOTAL_SECONDS = APPROACH_SECONDS + WORK_SECONDS + LEAVE_SECONDS
        fun safe(motion: MotionSnapshot) = motion.animationAllowed && !motion.restricted &&
            motion.parkGear == true && motion.speedMps?.let { it.isFinite() && abs(it) < .1f } == true

        /** Historical inbox items and ambiguous/stale stops must not stage a current visit. */
        fun eligible(visit: PlaceVisit, vehicle: String, user: String, now: Instant, fuelAllowed: Boolean) =
            visit.vehicleId == vehicle && visit.user == user && !visit.exited && visit.lastMatchedParked &&
                visit.state in listOf(VisitState.READY, VisitState.CONFIRMED) &&
                visit.lastAt <= now && visit.lastAt >= now.minusSeconds(30) &&
                (fuelAllowed || visit.place.kind != PlaceKind.FUEL)
    }
}
