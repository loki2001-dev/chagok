package io.chagok.app.roads

import io.chagok.app.domain.PlaceFix
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RoadMatcherTest {
    private val now=Instant.parse("2026-11-15T00:00:00Z")
    private val fix=PlaceFix(37.4,127.1,now)
    private fun road(grade: RoadGrade, offset: Double=0.0) = RoadSegment(1,grade,37.399,127.1+offset,37.401,127.1+offset)
    @Test fun recognizesMotorwayAndLocalRoad() {
        assertEquals(RoadGrade.MOTORWAY,RoadMatcher.match(fix,listOf(road(RoadGrade.MOTORWAY))).grade)
        assertEquals(RoadGrade.LOCAL,RoadMatcher.match(fix,listOf(road(RoadGrade.LOCAL))).grade)
    }
    @Test fun parallelDifferentGradesAreUnknown() {
        assertNull(RoadMatcher.match(fix,listOf(road(RoadGrade.MOTORWAY),road(RoadGrade.LOCAL,.0001))).grade)
    }
    @Test fun crossingAndElevatedRoadsAreUnknown() {
        val cross=RoadSegment(2,RoadGrade.LOCAL,37.4,127.099,37.4,127.101)
        assertNull(RoadMatcher.match(fix,listOf(road(RoadGrade.MOTORWAY),cross)).grade)
    }
    @Test fun sameGradeCarriagewaysCanShareGradeWithoutClaimingRoadIdentity() {
        assertEquals(RoadGrade.MOTORWAY,RoadMatcher.match(fix,listOf(road(RoadGrade.MOTORWAY),road(RoadGrade.MOTORWAY,.0001))).grade)
    }
    @Test fun offRoadAndMissingCoverageRemainUnknown() {
        assertNull(RoadMatcher.match(fix,emptyList()).grade)
        assertNull(RoadMatcher.match(fix,listOf(road(RoadGrade.MOTORWAY,.001))).grade)
    }
    @Test fun invalidAndOldOrFutureFixCannotBeUsed() {
        assertFalse(RoadMatcher.fresh(null,now))
        assertFalse(RoadMatcher.fresh(fix.copy(latitude=Double.NaN),now))
        assertFalse(RoadMatcher.fresh(fix.copy(at=now.minusSeconds(11)),now))
        assertFalse(RoadMatcher.fresh(fix.copy(at=now.plusSeconds(1)),now))
        assertTrue(RoadMatcher.fresh(fix,now))
    }
    @Test fun repeatedCachedFixDoesNotConfirmAndGradeChangeImmediatelyWithholds() {
        val stability=RoadStability()
        assertNull(stability.observe(RoadMatch(RoadGrade.MOTORWAY),now).grade)
        assertNull(stability.observe(RoadMatch(RoadGrade.MOTORWAY),now).grade)
        assertEquals(RoadGrade.MOTORWAY,stability.observe(RoadMatch(RoadGrade.MOTORWAY),now.plusSeconds(3)).grade)
        assertNull(stability.observe(RoadMatch(RoadGrade.LOCAL),now.plusSeconds(4)).grade)
        assertEquals(RoadGrade.LOCAL,stability.observe(RoadMatch(RoadGrade.LOCAL),now.plusSeconds(7)).grade)
        assertNull(stability.observe(RoadMatch(),now.plusSeconds(8)).grade)
    }
    @Test fun longGapAndResetRequireNewConfirmation() {
        val stability=RoadStability()
        stability.observe(RoadMatch(RoadGrade.ROAD),now)
        assertNull(stability.observe(RoadMatch(RoadGrade.ROAD),now.plusSeconds(11)).grade)
        stability.reset()
        assertNull(stability.observe(RoadMatch(RoadGrade.ROAD),now.plusSeconds(14)).grade)
    }
}
