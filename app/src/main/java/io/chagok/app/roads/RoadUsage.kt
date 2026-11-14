package io.chagok.app.roads

import io.chagok.app.domain.DiaryStore
import io.chagok.app.domain.Observation
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** OSM grade, never an assertion of urban/rural environment. UNKNOWN is retained in every total. */
enum class RoadBucket(val label: String) {
    MOTORWAY("고속도로"), TRUNK("간선도로"), ROAD("일반도로"), LOCAL("생활도로"),
    SERVICE("진입·구내도로"), TRACK("임도·농로"), LINK("연결도로"), UNKNOWN("미분류")
}
data class RoadAmount(val distanceKm: Double = 0.0, val movingMillis: Long = 0)
data class RoadUsageDay(val vehicleId: String, val date: LocalDate,
    val buckets: Map<RoadBucket, RoadAmount> = emptyMap(),
    val observationGapMillis: Long = 0, val distanceMissingMillis: Long = 0,
    val rejectedDistanceSamples: Long = 0)
data class RoadUsageCheckpoint(val at: Instant, val odometer: Observation?,
    val odometerSeenAt: Instant? = odometer?.receivedAt)
data class RoadUsageSummary(val from: LocalDate, val through: LocalDate,
    val buckets: Map<RoadBucket, RoadAmount>, val observationGapMillis: Long,
    val distanceMissingMillis: Long, val rejectedDistanceSamples: Long) {
    val totalDistanceKm get() = buckets.values.sumOf { it.distanceKm }
    val classifiedDistanceKm get() = totalDistanceKm-(buckets[RoadBucket.UNKNOWN]?.distanceKm ?: 0.0)
    // Environment evidence is not available in this dataset. Never map LOCAL->city or TRACK->rural.
    val environmentUnknownDistanceKm get() = totalDistanceKm
}

object RoadUsageQuery {
    /** Public consumer API: this app runs in one car; callers do not select a vehicle. */
    fun summary(store: DiaryStore, from: LocalDate, through: LocalDate): RoadUsageSummary =
        summary(store,requireNotNull(store.profile()) { "차량 프로필 초기화가 필요해요" }.id,from,through)

    internal fun summary(store: DiaryStore, vehicleId: String, from: LocalDate, through: LocalDate): RoadUsageSummary {
        require(from <= through)
        val days=store.roadUsage(vehicleId).filter { it.date >= from && it.date <= through }
        return RoadUsageSummary(from,through,RoadBucket.entries.associateWith { bucket ->
            RoadAmount(days.sumOf { it.buckets[bucket]?.distanceKm ?: 0.0 },days.sumOf { it.buckets[bucket]?.movingMillis ?: 0L })
        },days.sumOf { it.observationGapMillis },days.sumOf { it.distanceMissingMillis },days.sumOf { it.rejectedDistanceSamples })
    }
}

/** One writer. Distances come exclusively from the odometer, never GPS straight-line or speed estimates.
 * An odometer interval crossing grades, missing observations, or process restart is entirely UNKNOWN.
 * Daily accounting uses Asia/Seoul; an interval crossing midnight belongs to its ending date, UNKNOWN.
 * Both aggregate and checkpoint commit together, preventing replay/double count after process death.
 */
class RoadUsageRecorder(private val store: DiaryStore) {
    private var vehicle: String? = null
    private var checkpoint: RoadUsageCheckpoint? = null
    private var previousHealthy=false
    private var previousMoving=false
    private var previousBucket=RoadBucket.UNKNOWN
    private var intervalBucket=RoadBucket.UNKNOWN
    private var intervalContinuous=false
    private var started=false
    private var day: RoadUsageDay? = null
    fun sample(vehicleId: String, at: Instant, speedMps: Float?, healthy: Boolean,
        grade: RoadGrade?, km: Observation?) {
        if(vehicle != vehicleId) {
            vehicle=vehicleId; checkpoint=store.roadUsageCheckpoint(vehicleId)
            day=null; started=false; intervalContinuous=false
        }
        val old=checkpoint
        if(old != null && at <= old.at) return
        val date=at.atZone(ZONE).toLocalDate()
        var current=day?.takeIf { it.date == date } ?: store.roadUsage(vehicleId).firstOrNull { it.date == date } ?: RoadUsageDay(vehicleId,date)
        val elapsed=old?.let { Duration.between(it.at,at).toMillis() } ?: 0L
        val sameDay=old?.at?.atZone(ZONE)?.toLocalDate() == date
        val validMotion=healthy && speedMps?.isFinite() == true
        val continuous=started && validMotion && previousHealthy && elapsed in 1..5000
        val moving=healthy && speedMps?.let { it.isFinite() && kotlin.math.abs(it) > .5f } == true
        val bucket=grade?.let { RoadBucket.valueOf(it.name) } ?: RoadBucket.UNKNOWN
        if(!continuous || !sameDay || bucket != intervalBucket) intervalContinuous=false
        val fresh=km?.takeIf { it.value.isFinite() && it.value >= 0 && it.receivedAt <= at && it.receivedAt >= at.minusSeconds(45) }
        fun add(target: RoadBucket, distance: Double=0.0, millis: Long=0) {
            val before=current.buckets[target] ?: RoadAmount()
            current=current.copy(buckets=current.buckets+(target to RoadAmount(before.distanceKm+distance,before.movingMillis+millis)))
        }
        if(continuous && previousMoving && moving) {
            add(if(sameDay && bucket == previousBucket) bucket else RoadBucket.UNKNOWN,millis=elapsed)
            if(fresh == null) current=current.copy(distanceMissingMillis=current.distanceMissingMillis+elapsed)
        }
        if(old != null && (!started || elapsed > 5000 || !validMotion || !previousHealthy)) current=current.copy(observationGapMillis=current.observationGapMillis+elapsed)
        var odometer=old?.odometer
        var seenAt=old?.odometerSeenAt
        if(fresh != null && (seenAt == null || fresh.receivedAt > seenAt)) {
            seenAt=fresh.receivedAt
            val anchor=odometer
            val delta=anchor?.let { fresh.value-it.value } ?: 0.0
            val seconds=anchor?.let { Duration.between(it.receivedAt,fresh.receivedAt).toMillis()/1000.0 } ?: 0.0
            if(anchor == null || (delta >= 0 && delta <= seconds*.08+1.0)) {
                if(anchor != null) add(if(intervalContinuous && sameDay) intervalBucket else RoadBucket.UNKNOWN,distance=delta)
                odometer=fresh; intervalBucket=bucket; intervalContinuous=continuous
            } else {
                current=current.copy(rejectedDistanceSamples=current.rejectedDistanceSamples+1)
                // Retain the last accepted anchor across errors and restarts. Recovery distance is unknown.
                intervalContinuous=false
            }
        }
        val next=RoadUsageCheckpoint(at,odometer,seenAt)
        store.transaction { store.saveRoadUsage(current); store.saveRoadUsageCheckpoint(vehicleId,next) }
        checkpoint=next; day=current; started=true; previousHealthy=validMotion; previousMoving=moving; previousBucket=bucket
    }
    companion object { val ZONE: ZoneId = ZoneId.of("Asia/Seoul") }
}
