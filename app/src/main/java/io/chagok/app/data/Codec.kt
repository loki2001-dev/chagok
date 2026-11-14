package io.chagok.app.data

import io.chagok.app.domain.*
import io.chagok.app.domain.CandidateState
import io.chagok.app.domain.DetectionCandidate
import io.chagok.app.domain.DiaryEntry
import io.chagok.app.domain.EnergyKind
import io.chagok.app.domain.EntryStatus
import io.chagok.app.domain.FieldEvidence
import io.chagok.app.domain.Item
import io.chagok.app.domain.LearningProgress
import io.chagok.app.domain.MaintenancePlan
import io.chagok.app.domain.Observation
import io.chagok.app.domain.Powertrain
import io.chagok.app.domain.Revision
import io.chagok.app.domain.Source
import io.chagok.app.domain.Topic
import io.chagok.app.domain.VehicleProfile
import io.chagok.app.domain.Wheel
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** 도메인 객체 ↔ JSON. 저장 형식이 바뀌면 [VERSION]을 올린다 */
object Codec {
    const val VERSION = 3

    fun profile(p: VehicleProfile) = JSONObject()
        .put("id", p.id).put("name", p.name).put("powertrain", p.powertrain.name)
        .putOpt("model", p.model).putOpt("vin", p.vin)

    fun profile(o: JSONObject) = VehicleProfile(
        id = o.getString("id"),
        name = o.getString("name"),
        powertrain = Powertrain.valueOf(o.getString("powertrain")),
        model = o.optStringOrNull("model"),
        vin = o.optStringOrNull("vin"),
    )

    fun observation(v: Observation) = JSONObject().put("value", v.value).put("at", v.receivedAt.toString())
    fun observation(o: JSONObject) = Observation(o.getDouble("value"), Instant.parse(o.getString("at")))

    private fun evidence(e: FieldEvidence) = JSONObject()
        .put("value", e.value).put("unit", e.unit).put("source", e.source.name)
        .putOpt("at", e.receivedAt?.toString())
        .putOpt("observed", e.observed?.let(::observation))

    private fun evidence(o: JSONObject) = FieldEvidence(
        value = o.getDouble("value"),
        unit = o.getString("unit"),
        source = Source.valueOf(o.getString("source")),
        receivedAt = o.optStringOrNull("at")?.let(Instant::parse),
        observed = o.optJSONObject("observed")?.let(::observation),
    )

    fun entry(e: DiaryEntry) = JSONObject()
        .put("id", e.id).put("vehicleId", e.vehicleId).put("item", e.item.name)
        .put("performedOn", e.performedOn.toString()).put("createdAt", e.createdAt.toString())
        .putOpt("odometer", e.odometer?.let(::evidence))
        .putOpt("cost", e.cost).put("currency", e.currency).put("status", e.status.name)
        .put("author", e.author).put("version", e.version)
        .put("history", JSONArray(e.history.map { JSONObject().put("v", it.version).put("at", it.changedAt.toString()).put("s", it.summary) }))
        .put("wheels", JSONArray(e.wheels.map { it.name }))
        .putOpt("candidateId", e.candidateId).putOpt("spokenText", e.spokenText).putOpt("memo", e.memo)
        .put("updatedAt", e.updatedAt.toString()).putOpt("energyAmount", e.energyAmount)
        .putOpt("detachedObservation", e.detachedObservation?.let(::observation))

    fun entry(o: JSONObject) = DiaryEntry(
        id = o.getString("id"),
        vehicleId = o.getString("vehicleId"),
        item = Item.valueOf(o.getString("item")),
        performedOn = LocalDate.parse(o.getString("performedOn")),
        createdAt = Instant.parse(o.getString("createdAt")),
        odometer = o.optJSONObject("odometer")?.let(::evidence),
        cost = if (o.has("cost") && !o.isNull("cost")) o.getLong("cost") else null,
        currency = o.getString("currency"),
        status = EntryStatus.valueOf(o.getString("status")),
        author = o.getString("author"),
        version = o.getInt("version"),
        history = o.getJSONArray("history").objects().map { Revision(it.getInt("v"), Instant.parse(it.getString("at")), it.getString("s")) },
        wheels = o.getJSONArray("wheels").strings().map(Wheel::valueOf).toSet(),
        candidateId = o.optStringOrNull("candidateId"),
        spokenText = o.optStringOrNull("spokenText"),
        memo = o.optStringOrNull("memo"),
        updatedAt = o.optStringOrNull("updatedAt")?.let(Instant::parse)
            ?: o.getJSONArray("history").objects().lastOrNull()?.getString("at")?.let(Instant::parse)
            ?: Instant.parse(o.getString("createdAt")),
        energyAmount = if (o.has("energyAmount")) o.getDouble("energyAmount") else null,
        detachedObservation = o.optJSONObject("detachedObservation")?.let(::observation),
    )

    fun plan(p: MaintenancePlan) = JSONObject()
        .put("vehicleId", p.vehicleId).put("item", p.item.name)
        .putOpt("km", p.intervalKm).putOpt("months", p.intervalMonths)
        .put("source", p.source).put("setOn", p.setOn.toString())
        .put("notify", p.notificationsOn).putOpt("snoozed", p.snoozedUntil?.toString())

    fun plan(o: JSONObject) = MaintenancePlan(
        vehicleId = o.getString("vehicleId"),
        item = Item.valueOf(o.getString("item")),
        intervalKm = if (o.has("km") && !o.isNull("km")) o.getInt("km") else null,
        intervalMonths = if (o.has("months") && !o.isNull("months")) o.getInt("months") else null,
        source = o.getString("source"),
        setOn = LocalDate.parse(o.getString("setOn")),
        notificationsOn = o.getBoolean("notify"),
        snoozedUntil = o.optStringOrNull("snoozed")?.let(LocalDate::parse),
    )

    fun candidate(c: DetectionCandidate) = JSONObject()
        .put("id", c.id).put("vehicleId", c.vehicleId).put("kind", c.kind.name)
        .put("before", observation(c.before)).put("after", observation(c.after))
        .put("collecting",c.collecting).put("state", c.state.name).put("gapLong", c.gapLong).putOpt("linked", c.linkedEntryId)

    fun candidate(o: JSONObject) = DetectionCandidate(
        id = o.getString("id"),
        vehicleId = o.getString("vehicleId"),
        kind = EnergyKind.valueOf(o.getString("kind")),
        before = observation(o.getJSONObject("before")),
        after = observation(o.getJSONObject("after")),
        state = CandidateState.valueOf(o.getString("state")),
        gapLong = o.getBoolean("gapLong"),
        linkedEntryId = o.optStringOrNull("linked"),
        collecting = o.optBoolean("collecting",false),
    )

    fun progress(p: LearningProgress) = JSONObject()
        .put("user", p.user).put("topic", p.topic.name).put("contentVersion", p.contentVersion)
        .put("completedAt", p.completedAt.toString()).put("evidence", p.evidenceEntryId).put("review", p.needsReview)

    fun progress(o: JSONObject) = LearningProgress(
        user = o.getString("user"),
        topic = Topic.valueOf(o.getString("topic")),
        contentVersion = o.getInt("contentVersion"),
        completedAt = Instant.parse(o.getString("completedAt")),
        evidenceEntryId = o.getString("evidence"),
        needsReview = o.getBoolean("review"),
    )

    fun trip(t: DriveTrip) = JSONObject().put("id",t.id).put("vehicle",t.vehicleId).put("user",t.user)
        .put("start",t.startedAt.toString()).put("last",t.lastAt.toString()).putOpt("end",t.endedAt?.toString())
        .putOpt("startKm",t.startKm).putOpt("endKm",t.endKm).put("moving",t.movingSeconds).put("gap",t.hasGap)
        .putOpt("parked",t.parkedSince?.toString()).put("lastMoving",t.lastMoving).put("friends",JSONArray(t.friends.toList())).putOpt("odometerAt",t.odometerAt?.toString())
    fun trip(o: JSONObject) = DriveTrip(o.getString("id"),o.getString("vehicle"),o.getString("user"),
        Instant.parse(o.getString("start")),Instant.parse(o.getString("last")),o.optStringOrNull("end")?.let(Instant::parse),
        if(o.has("startKm"))o.getDouble("startKm")else null,if(o.has("endKm"))o.getDouble("endKm")else null,
        o.getLong("moving"),o.getBoolean("gap"),o.optStringOrNull("parked")?.let(Instant::parse),o.getBoolean("lastMoving"),o.getJSONArray("friends").strings().toSet(),o.optStringOrNull("odometerAt")?.let(Instant::parse))
    fun energyCheckpoint(v: EnergyCheckpoint) = JSONObject().putOpt("baseline",v.baseline?.let(::observation))
        .putOpt("anchor",v.anchor?.let(::observation)).putOpt("increase",v.lastIncrease?.toString())
        .putOpt("raw",v.lastRawAt?.toString()).put("gap",v.gapLong)
    fun energyCheckpoint(o: JSONObject) = EnergyCheckpoint(o.optJSONObject("baseline")?.let(::observation),
        o.optJSONObject("anchor")?.let(::observation),o.optStringOrNull("increase")?.let(Instant::parse),
        o.optStringOrNull("raw")?.let(Instant::parse),o.optBoolean("gap"))

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}
