package io.chagok.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.chagok.app.domain.DetectionCandidate
import io.chagok.app.domain.DiaryEntry
import io.chagok.app.domain.DiaryStore
import io.chagok.app.domain.EnergyKind
import io.chagok.app.domain.LearningProgress
import io.chagok.app.domain.MaintenancePlan
import io.chagok.app.domain.Observation
import io.chagok.app.domain.VehicleProfile
import org.json.JSONObject
import java.time.Instant

/**
 * 차량 안 로컬 DB (기획서 9장 '차량 안에서 시작'). 서버로 보내지 않는다.
 * 문서형 테이블 하나에 종류(kind)·키(id)·차량(owner)·JSON을 둔다.
 */
class SqliteStore(context: Context) : DiaryStore {
    private val helper = object : SQLiteOpenHelper(context, "chagok.db", null, Codec.VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE doc (kind TEXT NOT NULL, id TEXT NOT NULL, owner TEXT, json TEXT NOT NULL, PRIMARY KEY(kind, id))")
            db.execSQL("CREATE INDEX doc_owner ON doc(kind, owner)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun put(kind: String, id: String, owner: String?, json: JSONObject) {
        helper.writableDatabase.insertWithOnConflict(
            "doc", null,
            ContentValues().apply {
                put("kind", kind); put("id", id); put("owner", owner); put("json", json.toString())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
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
    override fun saveProgress(progress: LearningProgress) =
        put("progress", "${progress.user}/${progress.topic.name}", progress.user, Codec.progress(progress))

    override fun energyBaseline(vehicleId: String, kind: EnergyKind) =
        one("baseline", "$vehicleId/${kind.name}")?.let(Codec::observation)

    override fun saveEnergyBaseline(vehicleId: String, kind: EnergyKind, observation: Observation) =
        put("baseline", "$vehicleId/${kind.name}", vehicleId, Codec.observation(observation))

    override fun lastSeen(user: String) = one("seen", user)?.let { Instant.parse(it.getString("at")) }
    override fun saveLastSeen(user: String, at: Instant) = put("seen", user, null, JSONObject().put("at", at.toString()))

    /** 앱 설정용 작은 값 (현재 사용자 등) */
    fun setting(key: String): String? = one("setting", key)?.getString("v")
    fun saveSetting(key: String, value: String) = put("setting", key, null, JSONObject().put("v", value))
}
