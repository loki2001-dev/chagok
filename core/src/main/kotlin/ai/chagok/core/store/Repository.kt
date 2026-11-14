package ai.chagok.core.store

import ai.chagok.core.detect.EnergyAnchor
import ai.chagok.core.detect.EnergyCandidate
import ai.chagok.core.diary.DiaryEntry
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.learning.LearningProgress
import ai.chagok.core.learning.Topic
import ai.chagok.core.model.Member
import ai.chagok.core.model.Observation
import ai.chagok.core.model.VehicleProfile
import ai.chagok.core.model.VehicleSignal
import ai.chagok.core.schedule.MaintenancePlan
import java.time.Instant

/**
 * 저장 경계. 안드로이드는 로컬 DB(Room, 암호화)로, 테스트는 메모리로.
 * 원음은 저장하지 않고 좌표·경로는 기본 수집하지 않는다. 앱 재실행 뒤에도 남아야 하는 것만 여기 둔다.
 */
interface Repository {
    fun vehicles(): List<VehicleProfile>
    fun vehicle(id: String): VehicleProfile?
    fun saveVehicle(v: VehicleProfile)

    fun members(): List<Member>
    fun member(id: String): Member?
    fun saveMember(m: Member)

    fun entries(): List<DiaryEntry>
    fun entry(id: String): DiaryEntry?
    fun saveEntry(e: DiaryEntry)

    fun plans(vehicleId: String): List<MaintenancePlan>
    fun plan(vehicleId: String, item: MaintenanceItem): MaintenancePlan?
    fun savePlan(p: MaintenancePlan)

    fun candidates(): List<EnergyCandidate>
    fun candidate(id: String): EnergyCandidate?
    fun saveCandidate(c: EnergyCandidate)

    fun allProgress(): List<LearningProgress>
    fun progress(memberId: String): List<LearningProgress>
    fun progress(memberId: String, topic: Topic): LearningProgress?
    fun saveProgress(p: LearningProgress)

    fun anchor(vehicleId: String, signal: VehicleSignal): EnergyAnchor?
    fun saveAnchor(a: EnergyAnchor)

    /** 마지막으로 받은 유효 누적 주행거리. 재실행 뒤 역행 검사와 "마지막 수신" 표시에 쓴다. */
    fun lastOdometer(vehicleId: String): Observation?
    fun saveLastOdometer(o: Observation)

    fun feedSeen(memberId: String): Instant?
    fun saveFeedSeen(memberId: String, at: Instant)
}

class InMemoryRepository : Repository {
    private val vehicles = LinkedHashMap<String, VehicleProfile>()
    private val members = LinkedHashMap<String, Member>()
    private val entries = LinkedHashMap<String, DiaryEntry>()
    private val plans = LinkedHashMap<Pair<String, MaintenanceItem>, MaintenancePlan>()
    private val candidates = LinkedHashMap<String, EnergyCandidate>()
    private val progress = LinkedHashMap<Pair<String, Topic>, LearningProgress>()
    private val anchors = HashMap<Pair<String, VehicleSignal>, EnergyAnchor>()
    private val odometers = HashMap<String, Observation>()
    private val seen = HashMap<String, Instant>()

    override fun vehicles() = vehicles.values.toList()
    override fun vehicle(id: String) = vehicles[id]
    override fun saveVehicle(v: VehicleProfile) { vehicles[v.id] = v }
    override fun members() = members.values.toList()
    override fun member(id: String) = members[id]
    override fun saveMember(m: Member) { members[m.id] = m }
    override fun entries() = entries.values.toList()
    override fun entry(id: String) = entries[id]
    override fun saveEntry(e: DiaryEntry) { entries[e.id] = e }
    override fun plans(vehicleId: String) = plans.values.filter { it.vehicleId == vehicleId }
    override fun plan(vehicleId: String, item: MaintenanceItem) = plans[vehicleId to item]
    override fun savePlan(p: MaintenancePlan) { plans[p.vehicleId to p.item] = p }
    override fun candidates() = candidates.values.toList()
    override fun candidate(id: String) = candidates[id]
    override fun saveCandidate(c: EnergyCandidate) { candidates[c.id] = c }
    override fun allProgress() = progress.values.toList()
    override fun progress(memberId: String) = progress.values.filter { it.memberId == memberId }
    override fun progress(memberId: String, topic: Topic) = progress[memberId to topic]
    override fun saveProgress(p: LearningProgress) { progress[p.memberId to p.topic] = p }
    override fun anchor(vehicleId: String, signal: VehicleSignal) = anchors[vehicleId to signal]
    override fun saveAnchor(a: EnergyAnchor) { anchors[a.vehicleId to a.signal] = a }
    override fun lastOdometer(vehicleId: String) = odometers[vehicleId]
    override fun saveLastOdometer(o: Observation) { odometers[o.vehicleId] = o }
    override fun feedSeen(memberId: String) = seen[memberId]
    override fun saveFeedSeen(memberId: String, at: Instant) { seen[memberId] = at }
}
