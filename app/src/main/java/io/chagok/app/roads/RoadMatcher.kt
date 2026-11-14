package io.chagok.app.roads

import io.chagok.app.domain.PlaceFix
import io.chagok.app.domain.validCoordinates
import java.time.Instant
import kotlin.math.*

enum class RoadGrade(val label: String) {
    MOTORWAY("고속도로"), TRUNK("간선도로"), ROAD("일반도로"), LOCAL("생활도로"),
    SERVICE("진입·구내도로"), TRACK("임도·농로"), LINK("연결도로")
}

data class RoadSegment(val way: Long, val grade: RoadGrade,
    val aLat: Double, val aLon: Double, val bLat: Double, val bLon: Double)

data class RoadMatch(val grade: RoadGrade? = null, val reason: String = "도로 알 수 없음") {
    val label: String get() = grade?.let { "${it.label} · 지도 추정" } ?: reason
}

/** Conservative proximity estimate, not navigation/map matching or a legal road classification.
 * GPS has no documented metre accuracy on Pleos. Nearby competing grades always withhold a result.
 * Elevation is unavailable: an intersecting motorway and local road is therefore ambiguous.
 */
object RoadMatcher {
    fun fresh(fix: PlaceFix?, now: Instant): Boolean = fix != null &&
        validCoordinates(fix.latitude, fix.longitude) && fix.at <= now && fix.at >= now.minusSeconds(10)

    fun match(fix: PlaceFix, segments: List<RoadSegment>): RoadMatch {
        if(!validCoordinates(fix.latitude, fix.longitude)) return RoadMatch()
        val nearby = segments.map { it.grade to distance(fix, it) }.filter { it.second <= 37.0 }
        val closest = nearby.minByOrNull { it.second } ?: return RoadMatch()
        if(closest.second > 19.0) return RoadMatch()
        if(nearby.any { it.first != closest.first && it.second <= closest.second + 17.0 })
            return RoadMatch(reason = "도로 알 수 없음 · 인접 도로")
        return RoadMatch(closest.first)
    }

    private fun distance(fix: PlaceFix, segment: RoadSegment): Double {
        val xScale = 111_195.0 * cos(Math.toRadians(fix.latitude))
        val ax = (segment.aLon - fix.longitude) * xScale
        val ay = (segment.aLat - fix.latitude) * 111_195.0
        val bx = (segment.bLon - fix.longitude) * xScale
        val by = (segment.bLat - fix.latitude) * 111_195.0
        val dx = bx-ax; val dy = by-ay
        val length = dx*dx+dy*dy
        val t = if(length == 0.0) 0.0 else ((-ax*dx-ay*dy)/length).coerceIn(0.0,1.0)
        return hypot(ax+t*dx, ay+t*dy)
    }
}

/** Require two distinct observations spanning at least 3 seconds; never retain a stale grade. */
class RoadStability {
    private var grade: RoadGrade? = null
    private var since: Instant? = null
    private var last: Instant? = null
    private var result = RoadMatch()
    fun reset(): RoadMatch {
        grade=null; since=null; last=null; result=RoadMatch(); return result
    }
    fun observe(match: RoadMatch, at: Instant): RoadMatch {
        if(last != null && at <= last) return result
        if(last != null && at > last!!.plusSeconds(10)) reset()
        last=at
        if(match.grade == null) { grade=null; since=null; result=match; return result }
        if(grade != match.grade) { grade=match.grade; since=at }
        result=if(at >= since!!.plusSeconds(3)) match else RoadMatch(reason="도로 확인 중")
        return result
    }
}
