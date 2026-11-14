package io.chagok.app.roads

import io.chagok.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Drives the same worker -> main completion path used by RoadDetector and the service's recorder. */
class RoadDetectionEngineTest {
    private val start=Instant.parse("2026-11-15T00:00:00Z")
    private var now=start
    private val worker=ArrayDeque<() -> Unit>()
    private val main=ArrayDeque<() -> Unit>()
    private val store=MemoryStore()
    private val recorder=RoadUsageRecorder(store)
    private val completions=mutableListOf<Pair<Long,RoadGrade?>>()
    private val engine=RoadDetectionEngine(
        { fix -> when(fix.longitude) { 127.0 -> RoadMatch(RoadGrade.MOTORWAY)
            128.0 -> RoadMatch(RoadGrade.LOCAL); else -> RoadMatch() } },
        { worker.addLast(it) }, { main.addLast(it) }, { now }, {})
    private fun sample(second: Long, longitude: Double=127.0, km: Double?=100+second*.01, fixSecond: Long=second, enabled: Boolean=true) {
        now=start.plusSeconds(second)
        val at=now
        val observation=km?.let { Observation(it,at) }
        engine.sample(enabled,PlaceFix(37.4,longitude,start.plusSeconds(fixSecond)),at) { grade ->
            completions += second to grade
            recorder.sample("car",at,10f,true,grade,observation)
        }
    }
    private fun finish() { worker.removeFirst().invoke(); main.removeFirst().invoke() }
    private fun summary()=RoadUsageQuery.summary(store,"car",LocalDate.of(2026,11,15),LocalDate.of(2026,11,15))
    private fun establishHighway() {
        sample(0); finish()
        sample(3); finish()
        sample(4,fixSecond=3)
        assertEquals(RoadGrade.MOTORWAY,completions.last().second)
        assertEquals(.01,summary().buckets.getValue(RoadBucket.MOTORWAY).distanceKm,1e-9)
    }
    @Test fun newRoadAndOdometerWaitForLookupAndUseUnknownInsteadOfPreviousGrade() {
        for(lon in listOf(128.0,129.0)) {
            // Each independent engine must exercise an established highway -> other/unknown transition.
            val test=RoadDetectionEngineTest()
            test.establishHighway()
            test.sample(5,lon)
            assertEquals(4L,test.completions.last().first) // no premature odometer write
            test.worker.removeFirst().invoke()
            assertEquals(4L,test.completions.last().first) // even worker completion awaits main delivery
            test.main.removeFirst().invoke()
            assertNull(test.completions.last().second)
            assertEquals(.05,test.summary().totalDistanceKm,1e-9)
            assertEquals(.01,test.summary().buckets.getValue(RoadBucket.MOTORWAY).distanceKm,1e-9)
            assertEquals(.04,test.summary().buckets.getValue(RoadBucket.UNKNOWN).distanceKm,1e-9)
        }
    }
    @Test fun matchingNewHighwayFixStillClassifiesDistanceAfterCompletion() {
        establishHighway(); sample(5); finish()
        assertEquals(.02,summary().buckets.getValue(RoadBucket.MOTORWAY).distanceKm,1e-9)
        assertEquals(.05,summary().totalDistanceKm,1e-9)
    }
    @Test fun busyLookupAndOlderCompletionCannotReassignNewerTelemetry() {
        establishHighway()
        sample(5,128.0)
        sample(6,129.0) // bounded worker: new telemetry is explicitly unknown
        assertEquals(6L,completions.last().first)
        assertNull(completions.last().second)
        finish() // older callback cannot overwrite the newer checkpoint
        assertEquals(start.plusSeconds(6),store.roadUsageCheckpoint("car")!!.at)
        assertEquals(.06,summary().totalDistanceKm,1e-9)
        assertEquals(.01,summary().buckets.getValue(RoadBucket.MOTORWAY).distanceKm,1e-9)
    }
    @Test fun pendingNewLocationCannotFallBackToPreviouslyCachedGrade() {
        establishHighway(); sample(5,128.0)
        sample(6,127.0,fixSecond=3)
        assertNull(completions.last().second)
        finish()
        assertEquals(.06,summary().totalDistanceKm,1e-9)
        assertEquals(.01,summary().buckets.getValue(RoadBucket.MOTORWAY).distanceKm,1e-9)
    }
    @Test fun disablingWhileLookupRunsDoesNotRestoreOldGrade() {
        establishHighway(); sample(5)
        sample(6,enabled=false)
        finish()
        assertNull(completions.last().second)
        assertEquals(.01,summary().buckets.getValue(RoadBucket.MOTORWAY).distanceKm,1e-9)
    }
    @Test fun outOfOrderOrConflictingFixCannotReuseAnotherLocationsGrade() {
        establishHighway()
        sample(5,128.0,fixSecond=2)
        assertNull(completions.last().second)
        assertTrue(worker.isEmpty())
        sample(6,129.0,fixSecond=3)
        assertNull(completions.last().second)
        assertEquals(.01,summary().buckets.getValue(RoadBucket.MOTORWAY).distanceKm,1e-9)
    }
    @Test fun expiredResultIsUnknownAndClosedEngineCannotWrite() {
        establishHighway(); sample(5)
        now=start.plusSeconds(16); finish()
        assertNull(completions.last().second)
        sample(17); engine.close()
        val count=completions.size
        finish()
        assertEquals(count,completions.size)
    }
}
