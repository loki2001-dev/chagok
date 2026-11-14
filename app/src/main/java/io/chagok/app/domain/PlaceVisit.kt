package io.chagok.app.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.*

enum class PlaceKind(val label: String) { REPAIR("정비소"), FUEL("주유소") }

/** A catalogue POI, not proof that the vehicle entered the premises. */
data class ServicePlace(val id: String, val name: String, val kind: PlaceKind,
    val latitude: Double, val longitude: Double, val address: String, val source: String) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && source.isNotBlank())
        require(validCoordinates(latitude, longitude))
    }
}

data class PlaceFix(val latitude: Double, val longitude: Double, val at: Instant) {
    fun fresh(now: Instant) = validCoordinates(latitude, longitude) &&
        at >= now.minusSeconds(30) && at <= now
}

fun validCoordinates(latitude: Double, longitude: Double) =
    latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val lat = Math.toRadians(bLat - aLat)
    val lon = Math.toRadians(bLon - aLon)
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(lon / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

enum class VisitState { WATCHING, READY, CONFIRMED, EXCLUDED }

data class PlaceVisit(val id: String, val vehicleId: String, val user: String, val place: ServicePlace,
    val arrivedAt: Instant, val lastAt: Instant, val odometer: Observation?,
    val state: VisitState = VisitState.WATCHING, val observedSeconds: Long = 0,
    val hasGap: Boolean = false, val exited: Boolean = false, val linkedEntryId: String? = null,
    // Keep whole seconds for older readers; milliseconds are the accumulation source of truth.
    val observedMillis: Long = observedSeconds * 1000,
    val lastObservedAt: Instant = lastAt,
    val lastMatchedParked: Boolean = true)

/** One writer in VehicleMonitorService. Persist arrivals before ignition/process loss.
 * SDK confidence is not documented as accuracy in metres; do not invent an accuracy conversion.
 * All results remain questions. We do not record a GPS trail or infer a completed repair.
 */
class PlaceVisitTracker(private val store: DiaryStore) {
    private var scope: Pair<String, String>? = null
    private var previous: Instant? = null
    private var previousParked = false
    var status: String = "위치 대기"; private set

    fun sample(vehicle: String, user: String, fix: PlaceFix?, now: Instant, parked: Boolean?, km: Observation?, fuelAllowed: Boolean = true) {
        if (scope != (vehicle to user)) { scope = vehicle to user; previous = null; previousParked = false }
        // A known departure from P breaks stop recovery even when the location is cached/missing.
        if (parked == false) store.visits(vehicle).filter { it.user == user && !it.exited && it.lastMatchedParked }
            .forEach { store.saveVisit(it.copy(lastMatchedParked = false)) }
        if (fix == null || !fix.fresh(now)) {
            previousParked = false; status = "위치 미수신·오래된 위치 · 방문 판단 보류"; return
        }
        if (previous != null && !fix.at.isAfter(previous)) {
            if(parked != true) previousParked = false
            status = "새 위치 대기"; return
        }
        val elapsed = previous?.let { Duration.between(it, fix.at).toMillis() }
        val continuous = previousParked && parked == true && elapsed != null && elapsed in 1..20_000
        previous = fix.at; previousParked = parked == true
        val places = store.places()
        if (places.isEmpty()) { status = "정비소·주유소 목록을 먼저 내려받으세요"; return }
        val nearby = places.filter { (fuelAllowed || it.kind != PlaceKind.FUEL) && abs(it.latitude - fix.latitude) < .001 }
            .map { it to distanceMeters(fix.latitude, fix.longitude, it.latitude, it.longitude) }
            .filter { it.second <= ENTER_METERS }
        val match = nearby.singleOrNull()?.first
        status = when {
            nearby.size > 1 -> "가까운 업체가 여러 곳 · 방문 판단 보류"
            match != null -> "${match.name} 근처 · 방문 여부 확인 필요"
            else -> "근처에 등록된 정비소·주유소 없음"
        }
        store.transaction {
            val visits = store.visits(vehicle).filter { it.user == user }
            visits.filter { !it.exited }.forEach { v ->
                if (fix.at <= v.lastObservedAt) return@forEach
                val observedGap = Duration.between(v.lastObservedAt, fix.at).toMillis()
                val matchingStop = parked == true && match?.id == v.place.id
                val recovered = matchingStop && v.lastMatchedParked && observedGap >= RECOVERY_SECONDS * 1000
                val additionalMillis = if (continuous && matchingStop && v.lastMatchedParked && observedGap in 1..20_000)
                    observedGap else 0L
                val totalMillis = v.observedMillis + additionalMillis
                // Every fresh fix advances the observation clock, including driving and ambiguity.
                // This is distinct from lastAt, the last parked fix matching this particular place.
                val updated = v.copy(lastObservedAt = fix.at, lastMatchedParked = matchingStop,
                    lastAt = if (matchingStop) fix.at else v.lastAt,
                    hasGap = v.hasGap || observedGap > 20_000,
                    observedMillis = totalMillis, observedSeconds = totalMillis / 1000)
                val departed = parked != null &&
                    distanceMeters(fix.latitude, fix.longitude, v.place.latitude, v.place.longitude) > EXIT_METERS
                store.saveVisit(updated.copy(exited = departed, state = when {
                    v.state != VisitState.WATCHING -> v.state
                    totalMillis >= MIN_STOP_SECONDS * 1000 || recovered -> VisitState.READY
                    departed -> VisitState.EXCLUDED
                    else -> VisitState.WATCHING
                }))
            }
            // Unanswered historical visits stay in the inbox, but cannot suppress a new arrival.
            if (match != null && parked == true && visits.none { it.place.id == match.id && !it.exited }) {
                val freshKm = km?.takeIf { it.value.isFinite() && it.value >= 0 && Duration.between(it.receivedAt, now).seconds in 0..45 }
                store.saveVisit(PlaceVisit(UUID.randomUUID().toString(), vehicle, user, match, fix.at, fix.at, freshKm))
            }
        }
    }

    companion object {
        const val ENABLED = "visits.enabled"
        const val ENTER_METERS = 60.0
        const val EXIT_METERS = 150.0
        const val MIN_STOP_SECONDS = 60L
        const val RECOVERY_SECONDS = 120L
    }
}
