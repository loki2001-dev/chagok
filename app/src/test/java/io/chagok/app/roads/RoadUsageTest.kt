package io.chagok.app.roads

import io.chagok.app.domain.*
import io.chagok.app.data.Codec
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RoadUsageTest {
    private val start=Instant.parse("2026-11-15T00:00:00Z")
    private val store=MemoryStore()
    private var recorder=RoadUsageRecorder(store)
    private fun sample(second: Long, grade: RoadGrade?=RoadGrade.MOTORWAY, km: Double?=null, vehicle: String="car", speed: Float?=10f) {
        val at=start.plusSeconds(second)
        recorder.sample(vehicle,at,speed,speed!=null,grade,km?.let { Observation(it,at) })
    }
    private fun summary(vehicle: String="car")=RoadUsageQuery.summary(store,vehicle,LocalDate.of(2026,11,15),LocalDate.of(2026,11,15))
    @Test fun sumsOnlyNewOdometerIntervalsAndObservedMovingTime() {
        sample(0,km=100.0);sample(1,km=100.01);sample(2,km=100.02);sample(2,km=100.02)
        val total=summary()
        assertEquals(.02,total.totalDistanceKm,1e-9)
        assertEquals(.01,total.buckets.getValue(RoadBucket.UNKNOWN).distanceKm,1e-9)
        assertEquals(.01,total.buckets.getValue(RoadBucket.MOTORWAY).distanceKm,1e-9)
        assertEquals(2000,total.buckets.getValue(RoadBucket.MOTORWAY).movingMillis)
        assertEquals(.02,total.environmentUnknownDistanceKm,1e-9)
    }
    @Test fun gradeChangesBetweenOdometerReadsMakeWholeIntervalUnknown() {
        sample(0,km=100.0);sample(1,km=100.01)
        sample(2,RoadGrade.LOCAL);sample(3,RoadGrade.MOTORWAY);sample(4,km=100.04)
        assertEquals(.04,summary().buckets.getValue(RoadBucket.UNKNOWN).distanceKm,1e-9)
    }
    @Test fun restartDoesNotReplayAndRecoveryDistanceIsUnknown() {
        sample(0,km=100.0);sample(1,km=100.01);sample(2,km=100.02)
        recorder=RoadUsageRecorder(store)
        sample(2,km=100.02);sample(10,km=100.10)
        assertEquals(.10,summary().totalDistanceKm,1e-9)
        assertEquals(.09,summary().buckets.getValue(RoadBucket.UNKNOWN).distanceKm,1e-9)
        assertEquals(8000,summary().observationGapMillis)
    }
    @Test fun parkedOrMissingSpeedDoesNotAddMovingTimeAndMissingDistanceIsExposed() {
        sample(0);sample(1);sample(2,speed=0f);sample(3,speed=null)
        assertEquals(1000,summary().buckets.getValue(RoadBucket.MOTORWAY).movingMillis)
        assertEquals(1000,summary().distanceMissingMillis)
        assertEquals(1000,summary().observationGapMillis)
        assertEquals(0.0,summary().totalDistanceKm,0.0)
    }
    @Test fun resetAndJumpAreRejectedAndVehicleStatisticsStaySeparate() {
        sample(0,km=100.0);sample(1,km=2.0);sample(2,km=9999.0)
        sample(3,vehicle="other",km=50.0);sample(4,vehicle="other",km=50.01)
        assertEquals(2,summary().rejectedDistanceSamples)
        assertEquals(0.0,summary().totalDistanceKm,0.0)
        assertEquals(.01,summary("other").totalDistanceKm,1e-9)
    }
    @Test fun reverseGlitchRecoveryCountsOnlyTheActualDistance() {
        sample(0,km=100.0); sample(1,km=100.01)
        sample(2,km=99.50); sample(3,km=100.02)
        assertEquals(.02,summary().totalDistanceKm,1e-9)
        assertEquals(.02,summary().buckets.getValue(RoadBucket.UNKNOWN).distanceKm,1e-9)
        assertEquals(1L,summary().rejectedDistanceSamples)
        assertEquals(100.02,store.roadUsageCheckpoint("car")!!.odometer!!.value,0.0)
    }
    @Test fun cachedRejectedObservationIsCountedOnceEvenAfterRestart() {
        sample(0,km=100.0); sample(1,km=100.01); sample(2,km=99.50)
        val saved=Codec.roadCheckpoint(Codec.roadCheckpoint(store.roadUsageCheckpoint("car")!!))
        store.saveRoadUsageCheckpoint("car",saved)
        recorder=RoadUsageRecorder(store)
        recorder.sample("car",start.plusSeconds(3),10f,true,RoadGrade.MOTORWAY,Observation(99.50,start.plusSeconds(2)))
        sample(4,km=100.02)
        assertEquals(.02,summary().totalDistanceKm,1e-9)
        assertEquals(1L,summary().rejectedDistanceSamples)
    }
    @Test fun positiveSpikeDoesNotBecomeAnAnchorAndNormalDrivingResumes() {
        sample(0,km=100.0); sample(1,km=100.01); sample(2,km=9999.0)
        sample(3,km=100.02); sample(4,km=100.03)
        assertEquals(.03,summary().totalDistanceKm,1e-9)
        assertEquals(1L,summary().rejectedDistanceSamples)
        assertEquals(.01,summary().buckets.getValue(RoadBucket.MOTORWAY).distanceKm,1e-9)
    }
    @Test fun badFirstZeroAnchorIsReplacedWithoutCountingTheJump() {
        // SDK not ready: the very first reading is 0. Real readings must not wait days and then add 100,000 km.
        sample(0,km=0.0)
        sample(1,km=100_000.0); sample(2,km=100_000.01); sample(3,km=100_000.02)
        assertEquals(100_000.02,store.roadUsageCheckpoint("car")!!.odometer!!.value,0.0)
        assertEquals(0.0,summary().totalDistanceKm,0.0)
        sample(4,km=100_000.03)
        assertEquals(.01,summary().totalDistanceKm,1e-9)
        assertEquals(3L,summary().rejectedDistanceSamples)
    }
    @Test fun spikeAsFirstAnchorDoesNotBlockDistanceForever() {
        sample(0,km=9999.0)
        for(second in 1L..4L) sample(second,km=100.0+(second-1)*.01)
        assertEquals(.01,summary().totalDistanceKm,1e-9)
        assertEquals(100.03,store.roadUsageCheckpoint("car")!!.odometer!!.value,1e-9)
    }
    @Test fun inconsistentGlitchesNeverOutvoteAGoodAnchor() {
        sample(0,km=100.0)
        sample(1,km=2.0); sample(2,km=9999.0); sample(3,km=2.0); sample(4,km=9999.0)
        assertEquals(100.0,store.roadUsageCheckpoint("car")!!.odometer!!.value,0.0)
        sample(5,km=100.05)
        assertEquals(.05,summary().totalDistanceKm,1e-9)
        assertEquals(4L,summary().rejectedDistanceSamples)
    }
    @Test fun v6CheckpointUsesAcceptedObservationAsSeenTimestamp() {
        val old=org.json.JSONObject().put("at",start.toString())
            .put("odometer",Codec.observation(Observation(100.0,start)))
        assertEquals(start,Codec.roadCheckpoint(old).odometerSeenAt)
    }
    @Test fun disabledRoadDetectionRetainsUnknownDistance() {
        sample(0,null,100.0);sample(1,null,100.01);sample(2,null,100.02)
        assertEquals(.02,summary().buckets.getValue(RoadBucket.UNKNOWN).distanceKm,1e-9)
        assertEquals(0.0,summary().classifiedDistanceKm,1e-9)
    }
    @Test fun aggregateAndCheckpointSerializationRoundTrip() {
        sample(0,km=100.0);sample(1,km=100.01)
        val day=store.roadUsage("car").single()
        assertEquals(day,Codec.roadUsage(Codec.roadUsage(day)))
        val checkpoint=store.roadUsageCheckpoint("car")!!
        assertEquals(checkpoint,Codec.roadCheckpoint(Codec.roadCheckpoint(checkpoint)))
    }
    @Test fun midnightIntervalIsUnknownAndPublicApiUsesInstalledProfile() {
        store.saveProfile(VehicleProfile("car","차곡",Powertrain.ICE))
        val midnight=Instant.parse("2026-11-15T15:00:00Z")
        for(second in -2L..0L) recorder.sample("car",midnight.plusSeconds(second),10f,true,RoadGrade.MOTORWAY,
            Observation(100+second*.01,midnight.plusSeconds(second)))
        val date=LocalDate.of(2026,11,16)
        val result=RoadUsageQuery.summary(store,date,date)
        assertEquals(.01,result.buckets.getValue(RoadBucket.UNKNOWN).distanceKm,1e-9)
        assertEquals(1000L,result.buckets.getValue(RoadBucket.UNKNOWN).movingMillis)
    }
    @Test fun staleOdometerAndBackwardSamplesAreIgnored() {
        sample(0,km=100.0);sample(1,km=100.01)
        recorder.sample("car",start.plusSeconds(60),10f,true,RoadGrade.MOTORWAY,Observation(200.0,start))
        sample(0,km=200.0)
        assertEquals(.01,summary().totalDistanceKm,1e-9)
    }
}
