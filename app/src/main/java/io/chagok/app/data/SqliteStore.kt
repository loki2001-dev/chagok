package io.chagok.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.chagok.app.domain.*
import io.chagok.app.roads.*
import io.chagok.app.domain.DetectionCandidate
import io.chagok.app.domain.DiaryEntry
import io.chagok.app.domain.DiaryStore
import io.chagok.app.domain.EnergyKind
import io.chagok.app.domain.LearningProgress
import io.chagok.app.domain.MaintenancePlan
import io.chagok.app.domain.Observation
import io.chagok.app.domain.VehicleProfile
import io.chagok.app.domain.SharingPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import io.chagok.app.companion.Encounters
import io.chagok.app.companion.EncounterRecord

/**
 * 차량 안 로컬 DB (기획서 9장 '차량 안에서 시작'). 서버로 보내지 않는다.
 * 문서형 테이블 하나에 종류(kind)·키(id)·차량(owner)·JSON을 둔다.
 */
class SqliteStore(context: Context, databaseName: String = "chagok.db") : DiaryStore {
    private val helper = object : SQLiteOpenHelper(context, databaseName, null, Codec.VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE doc (kind TEXT NOT NULL, id TEXT NOT NULL, owner TEXT, json TEXT NOT NULL, PRIMARY KEY(kind, id))")
            db.execSQL("CREATE INDEX doc_owner ON doc(kind, owner)")
        }

        // New document types and optional JSON fields need no table migration.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion <= newVersion && newVersion <= Codec.VERSION)
        }
    }

    override fun <T> transaction(block: () -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    private var cachedPlaces: Pair<String?, List<ServicePlace>>? = null
    override fun places(): List<ServicePlace> {
        val revision = setting("places.revision")
        cachedPlaces?.takeIf { it.first == revision }?.let { return it.second }
        return all("place", "catalogue").map(Codec::place).also { cachedPlaces = revision to it }
    }
    override fun replacePlaces(places: List<ServicePlace>) = transaction {
        helper.writableDatabase.delete("doc", "kind=?", arrayOf("place"))
        places.forEach { put("place", it.id, "catalogue", Codec.place(it)) }
        saveSetting("places.revision", java.util.UUID.randomUUID().toString())
        cachedPlaces = null
    }
    override fun visits(vehicleId: String) = all("visit", vehicleId).map(Codec::visit)
    override fun saveVisit(visit: PlaceVisit) = put("visit", visit.id, visit.vehicleId, Codec.visit(visit))

    fun close() = helper.close()

    private fun put(kind: String, id: String, owner: String?, json: JSONObject) {
        val inserted = helper.writableDatabase.insertWithOnConflict(
            "doc", null,
            ContentValues().apply {
                put("kind", kind); put("id", id); put("owner", owner); put("json", json.toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(inserted != -1L) { "기록을 저장하지 못했어요. 저장 공간을 확인해 주세요." }
    }

    private fun one(kind: String, id: String): JSONObject? =
        helper.readableDatabase.rawQuery("SELECT json FROM doc WHERE kind=? AND id=?", arrayOf(kind, id)).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else null
        }

    private fun all(kind: String, owner: String): List<JSONObject> =
        helper.readableDatabase.rawQuery("SELECT json FROM doc WHERE kind=? AND owner=?", arrayOf(kind, owner)).use {
            buildList { while (it.moveToNext()) add(JSONObject(it.getString(0))) }
        }

    override fun profile(): VehicleProfile? = one("profile", "current")?.let(Codec::profile)
    override fun saveProfile(profile: VehicleProfile) = put("profile", "current", null, Codec.profile(profile))

    override fun entries(vehicleId: String) = all("entry", vehicleId).map(Codec::entry)
    override fun entry(id: String) = one("entry", id)?.let(Codec::entry)
    override fun saveEntry(entry: DiaryEntry) = put("entry", entry.id, entry.vehicleId, Codec.entry(entry))
    override fun deleteEntry(id: String) {
        helper.writableDatabase.delete("doc", "kind=? AND id=?", arrayOf("entry", id))
    }

    override fun plans(vehicleId: String) = all("plan", vehicleId).map(Codec::plan)
    override fun savePlan(plan: MaintenancePlan) = put("plan", "${plan.vehicleId}/${plan.item.name}", plan.vehicleId, Codec.plan(plan))

    override fun candidates(vehicleId: String) = all("candidate", vehicleId).map(Codec::candidate)
    override fun saveCandidate(candidate: DetectionCandidate) =
        put("candidate", candidate.id, candidate.vehicleId, Codec.candidate(candidate))

    override fun progress(user: String) = all("progress", user).map(Codec::progress)
    override fun allProgress(): List<LearningProgress> =
        helper.readableDatabase.rawQuery("SELECT json FROM doc WHERE kind=?", arrayOf("progress")).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Codec.progress(JSONObject(cursor.getString(0)))) }
        }
    override fun sharing(author: String): SharingPolicy {
        val o = one("sharing", author) ?: return SharingPolicy(author)
        val readers = o.optJSONArray("readers")
        return SharingPolicy(author, readers?.let { (0 until it.length()).map(it::getString).toSet() })
    }
    override fun saveSharing(policy: SharingPolicy) = put("sharing", policy.author, null,
        JSONObject().putOpt("readers", policy.readers?.let { JSONArray(it.toList()) }))
    override fun saveProgress(progress: LearningProgress) =
        put("progress", "${progress.user}/${progress.topic.name}", progress.user, Codec.progress(progress))

    override fun energyBaseline(vehicleId: String, kind: EnergyKind) =
        one("baseline", "$vehicleId/${kind.name}")?.let(Codec::observation)

    override fun saveEnergyBaseline(vehicleId: String, kind: EnergyKind, observation: Observation) =
        put("baseline", "$vehicleId/${kind.name}", vehicleId, Codec.observation(observation))

    override fun lastSeen(user: String) = one("seen", user)?.let { Instant.parse(it.getString("at")) }
    override fun saveLastSeen(user: String, at: Instant) = put("seen", user, null, JSONObject().put("at", at.toString()))

    /** 앱 설정용 작은 값 (현재 사용자 등) */
    override fun setting(key: String): String? = one("setting", key)?.getString("v")
    override fun saveSetting(key: String, value: String) = put("setting", key, null, JSONObject().put("v", value))

    override fun roadUsage(vehicleId: String) = all("road-usage",vehicleId).map(Codec::roadUsage)
    override fun saveRoadUsage(day: RoadUsageDay) = put("road-usage","${day.vehicleId}/${day.date}",day.vehicleId,Codec.roadUsage(day))
    override fun roadUsageCheckpoint(vehicleId: String) = one("road-checkpoint",vehicleId)?.let(Codec::roadCheckpoint)
    override fun saveRoadUsageCheckpoint(vehicleId: String, checkpoint: RoadUsageCheckpoint) = put("road-checkpoint",vehicleId,vehicleId,Codec.roadCheckpoint(checkpoint))
    override fun trips(vehicleId: String) = all("trip",vehicleId).map(Codec::trip)
    override fun saveTrip(trip: DriveTrip) = put("trip",trip.id,trip.vehicleId,Codec.trip(trip))
    override fun gaps(vehicleId: String) = all("gap",vehicleId).map { RecordingGap(it.getString("id"),vehicleId,Instant.parse(it.getString("from")),Instant.parse(it.getString("to")),it.getString("reason")) }
    override fun saveGap(gap: RecordingGap) = put("gap",gap.id,gap.vehicleId,JSONObject().put("id",gap.id).put("from",gap.from.toString()).put("to",gap.to.toString()).put("reason",gap.reason))
    override fun energyCheckpoint(vehicleId: String, kind: EnergyKind) = one("energy-checkpoint","$vehicleId/${kind.name}")?.let(Codec::energyCheckpoint)
    override fun saveEnergyCheckpoint(vehicleId: String, kind: EnergyKind, value: EnergyCheckpoint) = put("energy-checkpoint","$vehicleId/${kind.name}",vehicleId,Codec.energyCheckpoint(value))

    fun encounters(vehicleId: String, user: String): List<EncounterRecord> =
        all("encounter", "$vehicleId/$user").map { json ->
            EncounterRecord(json.getString("scene"), json.getInt("count"),
                Instant.parse(json.getString("first")), Instant.parse(json.getString("last")))
        }.filter { Encounters.find(it.id) != null }

    /** Receipt + collection update commit together. Replayed frame/lifecycle callbacks are idempotent. */
    fun recordEncounter(vehicleId: String, user: String, scene: String, token: String, at: Instant): Boolean = transaction {
        require(Encounters.find(scene) != null && token.isNotBlank() && token.length <= 100)
        val owner = "$vehicleId/$user"
        val receipt = "$owner/$token"
        if (one("encounter_receipt", receipt) != null) return@transaction false
        val key = "$owner/$scene"
        val old = one("encounter", key)
        put("encounter", key, owner, JSONObject().put("scene", scene)
            .put("count", ((old?.optInt("count") ?: 0) + 1).coerceAtMost(99999))
            .put("first", old?.optString("first") ?: at.toString()).put("last", at.toString()))
        put("encounter_receipt", receipt, owner, JSONObject().put("scene", scene).put("at", at.toString()))
        trips(vehicleId).firstOrNull { it.user==user&&it.endedAt==null }?.let { saveTrip(it.copy(friends=it.friends+scene)) }
        true
    }
}
