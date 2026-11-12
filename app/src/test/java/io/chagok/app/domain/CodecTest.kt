package io.chagok.app.domain

import io.chagok.app.data.Codec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** 재실행 후 데이터 유지: 저장 형식 왕복 */
class CodecTest {
    private val at = Instant.parse("2026-11-08T01:00:00Z")

    private fun <T> roundTrip(v: T, enc: (T) -> JSONObject, dec: (JSONObject) -> T) =
        assertEquals(v, dec(JSONObject(enc(v).toString())))

    @Test
    fun entry() {
        val full = DiaryEntry(
            id = "e1", vehicleId = "car", item = Item.TIRE_REPLACE, performedOn = LocalDate.of(2026, 11, 8), createdAt = at,
            odometer = FieldEvidence(47_150.0, "km", Source.USER, observed = Observation(47_200.0, at)),
            cost = 320_000, status = EntryStatus.CONFIRMED, author = "나", version = 2,
            history = listOf(Revision(2, at, "비용 수정")), wheels = setOf(Wheel.FRONT_LEFT, Wheel.FRONT_RIGHT),
            candidateId = "c1", spokenText = "앞 타이어 두 개 교체했어", memo = "메모",
        )
        roundTrip(full, Codec::entry, Codec::entry)
        roundTrip(full.copy(odometer = null, cost = null, candidateId = null, spokenText = null, memo = null, wheels = emptySet()), Codec::entry, Codec::entry)
    }

    @Test
    fun others() {
        roundTrip(VehicleProfile("car", "우리 차", Powertrain.EV, null, null), Codec::profile, Codec::profile)
        roundTrip(MaintenancePlan("car", Item.ENGINE_OIL, 10_000, null, "매뉴얼", LocalDate.of(2026, 1, 1), false, LocalDate.of(2026, 2, 1)), Codec::plan, Codec::plan)
        roundTrip(DetectionCandidate("c1", "car", EnergyKind.FUEL, Observation(30.0, at), Observation(80.0, at.plusSeconds(600)), CandidateState.PENDING, true), Codec::candidate, Codec::candidate)
        roundTrip(LearningProgress("나", Topic.FIRST_DIARY, 1, at, "e1", true), Codec::progress, Codec::progress)
    }
}
