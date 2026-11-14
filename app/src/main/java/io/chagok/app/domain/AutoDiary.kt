package io.chagok.app.domain

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Observed driving, never a reconstructed route. Missing samples are not counted as moving time. */
data class DriveTrip(
    val id: String, val vehicleId: String, val user: String,
    val startedAt: Instant, val lastAt: Instant, val endedAt: Instant? = null,
    val startKm: Double? = null, val endKm: Double? = null,
    val movingSeconds: Long = 0, val hasGap: Boolean = false,
    val parkedSince: Instant? = null, val lastMoving: Boolean = false,
    val friends: Set<String> = emptySet(),
    val odometerAt: Instant? = null,
) {
    val distanceKm: Double? get() = if(startKm != null && endKm != null && endKm >= startKm) endKm-startKm else null
}

data class RecordingGap(val id: String, val vehicleId: String, val from: Instant, val to: Instant, val reason: String)

/** One writer in the monitor. Checkpoints survive process death; short Park stops remain one trip. */
class TripRecorder(private val store: DiaryStore, private val parkSeconds: Long = 120) {
    private var active: DriveTrip? = null
    private var loadedVehicle: String? = null
    private var lastSaved: Instant? = null
    fun sample(vehicle: String, user: String, at: Instant, speedMps: Float?, parked: Boolean?, km: Observation?): DriveTrip? {
        if(loadedVehicle != vehicle) {
            active=store.trips(vehicle).filter { it.endedAt==null }.maxByOrNull { it.startedAt }
            loadedVehicle=vehicle;lastSaved=null
        }
        val moving=speedMps?.let { it.isFinite() && kotlin.math.abs(it)>0.5f } == true
        val fresh=km?.takeIf { it.value.isFinite() && it.value>=0 && Duration.between(it.receivedAt,at).seconds in 0..45 }
        val freshKm=fresh?.value
        var t=active
        if(t!=null && (t.user!=user || Duration.between(t.lastAt,at).seconds>1800 || at<t.lastAt)) {
            store.saveTrip(t.copy(endedAt=t.parkedSince ?: t.lastAt,hasGap=true));active=null;t=null
        }
        if(t==null) {
            if(!moving)return null
            t=DriveTrip(UUID.randomUUID().toString(),vehicle,user,at,at,startKm=freshKm,endKm=freshKm,lastMoving=true,hasGap=freshKm==null,odometerAt=fresh?.receivedAt)
        } else {
            val elapsed=Duration.between(t.lastAt,at).seconds
            val continuous=elapsed in 0..5 && speedMps!=null && parked!=null
            // Do not attach an old, large odometer jump to an apparently short trip.
            // Distance is read less frequently than speed; compare odometer observation times.
            val distanceSeconds=if(fresh!=null)Duration.between(t.odometerAt ?: t.lastAt,fresh.receivedAt).seconds.coerceAtLeast(0)else 0
            val plausible=freshKm==null || t.endKm==null || (freshKm>=t.endKm && freshKm-t.endKm<=maxOf(1.0,distanceSeconds*0.08+1))
            val stopped=if(parked==true&&!moving)t.parkedSince ?: at else null
            t=t.copy(lastAt=at,endKm=if(plausible)freshKm ?: t.endKm else t.endKm,
                movingSeconds=t.movingSeconds+if(continuous&&moving&&t.lastMoving)elapsed else 0,
                hasGap=t.hasGap||!continuous||!plausible||freshKm==null,
                parkedSince=stopped,lastMoving=moving,odometerAt=if(plausible)fresh?.receivedAt ?: t.odometerAt else t.odometerAt)
            if(stopped!=null&&Duration.between(stopped,at).seconds>=parkSeconds)t=t.copy(endedAt=stopped)
        }
        active=t
        if(lastSaved==null||Duration.between(lastSaved,at).seconds>=5||t.endedAt!=null) {
            // Encounter collection can update the same trip between checkpoints.
            t=t.copy(friends=t.friends+(store.trips(vehicle).firstOrNull { it.id==t.id }?.friends ?: emptySet()))
            store.saveTrip(t);active=t;lastSaved=at
        }
        if(t.endedAt!=null)active=null
        return t
    }
    fun stop(at: Instant) {
        active?.let { t -> store.saveTrip(t.copy(endedAt=t.parkedSince ?: t.lastAt,hasGap=true,
            friends=t.friends+(store.trips(t.vehicleId).firstOrNull { it.id==t.id }?.friends ?: emptySet()))) }
        active=null
    }
}

/** Persistent per-user question policy. Deferral requires a later parking transition. */
class QuestionQueue(private val store: DiaryStore, private val vehicle: String, private val user: String,
    private val clock: Clock = Clock.systemDefaultZone()) {
    private val prefix="questions/$vehicle/$user/"
    fun muted()=store.setting(prefix+"mute")==LocalDate.now(clock).toString()
    fun muteToday() { store.saveSetting(prefix+"mute",LocalDate.now(clock).toString()) }
    fun unmute() { store.saveSetting(prefix+"mute","");store.saveSetting(prefix+"lastParking","") }
    private fun parking()=store.setting("monitor.parkSequence")?.toLongOrNull() ?: 0L
    fun offered(id: String) {
        store.saveSetting(prefix+id,"${parking()}|${clock.instant()}")
        store.saveSetting(prefix+"lastParking",parking().toString())
    }
    fun canOffer(parked: Boolean) = parked && !muted() && store.setting(prefix+"lastParking") != parking().toString()
    fun next(candidates: List<DetectionCandidate>, parked: Boolean): DetectionCandidate? {
        if(!parked||muted()||store.setting(prefix+"lastParking")==parking().toString())return null
        return candidates.filter { it.state==CandidateState.PENDING&&!it.collecting }.sortedBy { it.after.receivedAt }.firstOrNull { c ->
            val previous=store.setting(prefix+c.id)?.split('|')
            previous==null || ((previous.firstOrNull()?.toLongOrNull() ?: Long.MAX_VALUE)<parking() &&
                previous.getOrNull(1)?.let { runCatching { Duration.between(Instant.parse(it),clock.instant()).seconds>=60 }.getOrDefault(false) }==true)
        }
    }
}
