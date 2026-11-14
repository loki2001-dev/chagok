package io.chagok.app.domain

import java.time.Instant

/** 일지 저장소. 앱은 SQLite, 단위 시험은 [MemoryStore]를 쓴다 */
interface DiaryStore {
    fun <T> transaction(block: () -> T): T
    fun setting(key: String): String?
    fun saveSetting(key: String, value: String)
    fun trips(vehicleId: String): List<DriveTrip>
    fun saveTrip(trip: DriveTrip)
    fun gaps(vehicleId: String): List<RecordingGap>
    fun saveGap(gap: RecordingGap)
    fun energyCheckpoint(vehicleId: String, kind: EnergyKind): EnergyCheckpoint?
    fun saveEnergyCheckpoint(vehicleId: String, kind: EnergyKind, value: EnergyCheckpoint)
    fun profile(): VehicleProfile?
    fun saveProfile(profile: VehicleProfile)

    fun entries(vehicleId: String): List<DiaryEntry>
    fun entry(id: String): DiaryEntry?
    fun saveEntry(entry: DiaryEntry)
    fun deleteEntry(id: String)

    fun plans(vehicleId: String): List<MaintenancePlan>
    fun savePlan(plan: MaintenancePlan)

    fun candidates(vehicleId: String): List<DetectionCandidate>
    fun saveCandidate(candidate: DetectionCandidate)

    fun progress(user: String): List<LearningProgress>
    fun saveProgress(progress: LearningProgress)
    fun allProgress(): List<LearningProgress>
    fun sharing(author: String): SharingPolicy
    fun saveSharing(policy: SharingPolicy)

    fun energyBaseline(vehicleId: String, kind: EnergyKind): Observation?
    fun saveEnergyBaseline(vehicleId: String, kind: EnergyKind, observation: Observation)

    /** 공용 일지에서 이 사용자가 마지막으로 변경 사항을 확인한 시각 */
    fun lastSeen(user: String): Instant?
    fun saveLastSeen(user: String, at: Instant)
}

class MemoryStore : DiaryStore {
    override fun <T> transaction(block: () -> T): T = block()
    private var profile: VehicleProfile? = null
    private val entries = linkedMapOf<String, DiaryEntry>()
    private val plans = linkedMapOf<Pair<String, Item>, MaintenancePlan>()
    private val candidates = linkedMapOf<String, DetectionCandidate>()
    private val progress = linkedMapOf<Pair<String, Topic>, LearningProgress>()
    private val baselines = mutableMapOf<Pair<String, EnergyKind>, Observation>()
    private val seen = mutableMapOf<String, Instant>()
    private val sharing = mutableMapOf<String, SharingPolicy>()

    private val settings = mutableMapOf<String,String>()
    private val trips = linkedMapOf<String,DriveTrip>()
    private val gaps = linkedMapOf<String,RecordingGap>()
    private val checkpoints = mutableMapOf<Pair<String,EnergyKind>,EnergyCheckpoint>()
    override fun setting(key: String) = settings[key]
    override fun saveSetting(key: String, value: String) { settings[key]=value }
    override fun trips(vehicleId: String) = trips.values.filter { it.vehicleId==vehicleId }
    override fun saveTrip(trip: DriveTrip) { trips[trip.id]=trip }
    override fun gaps(vehicleId: String) = gaps.values.filter { it.vehicleId==vehicleId }
    override fun saveGap(gap: RecordingGap) { gaps[gap.id]=gap }
    override fun energyCheckpoint(vehicleId: String, kind: EnergyKind) = checkpoints[vehicleId to kind]
    override fun saveEnergyCheckpoint(vehicleId: String, kind: EnergyKind, value: EnergyCheckpoint) { checkpoints[vehicleId to kind]=value }

    override fun profile() = profile
    override fun saveProfile(profile: VehicleProfile) {
        this.profile = profile
    }

    override fun entries(vehicleId: String) = entries.values.filter { it.vehicleId == vehicleId }
    override fun entry(id: String) = entries[id]
    override fun saveEntry(entry: DiaryEntry) {
        entries[entry.id] = entry
    }

    override fun deleteEntry(id: String) {
        entries.remove(id)
    }

    override fun plans(vehicleId: String) = plans.values.filter { it.vehicleId == vehicleId }
    override fun savePlan(plan: MaintenancePlan) {
        plans[plan.vehicleId to plan.item] = plan
    }

    override fun candidates(vehicleId: String) = candidates.values.filter { it.vehicleId == vehicleId }
    override fun saveCandidate(candidate: DetectionCandidate) {
        candidates[candidate.id] = candidate
    }

    override fun progress(user: String) = progress.values.filter { it.user == user }
    override fun allProgress() = progress.values.toList()
    override fun sharing(author: String) = sharing[author] ?: SharingPolicy(author)
    override fun saveSharing(policy: SharingPolicy) { sharing[policy.author] = policy }
    override fun saveProgress(progress: LearningProgress) {
        this.progress[progress.user to progress.topic] = progress
    }

    override fun energyBaseline(vehicleId: String, kind: EnergyKind) = baselines[vehicleId to kind]
    override fun saveEnergyBaseline(vehicleId: String, kind: EnergyKind, observation: Observation) {
        baselines[vehicleId to kind] = observation
    }

    override fun lastSeen(user: String) = seen[user]
    override fun saveLastSeen(user: String, at: Instant) {
        seen[user] = at
    }
}
