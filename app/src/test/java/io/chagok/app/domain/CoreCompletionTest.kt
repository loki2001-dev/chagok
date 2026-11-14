package io.chagok.app.domain

import io.chagok.app.data.Codec
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CoreCompletionTest {
    private val at = Instant.parse("2026-11-14T01:00:00Z")
    private val store = MemoryStore()
    private val service = DiaryService(store, Clock.fixed(at, ZoneOffset.ofHours(9)))
    private fun draft(author: String = "A", item: Item = Item.ENGINE_OIL) =
        service.manualDraft(item, service.today(), 80000, Reading.Value(Observation(47200.0, at)), author)

    @Test fun firstStampIsGrantedOnceOnConfirmation() {
        val d = draft(); assertTrue(service.stamps("A").isEmpty())
        service.confirm(d.id)
        assertEquals(Topic.FIRST_DIARY, service.stamps("A").single().topic)
        assertTrue(service.confirm(d.id) is DiaryService.ConfirmResult.Rejected)
        assertEquals(1, service.stamps("A").size)
        service.cancel(d.id)
        assertTrue(service.stamps("A").single().needsReview)
    }

    @Test fun editConfirmedRecordRecalculatesLedgerAndSchedule() {
        service.setPlan(Item.ENGINE_OIL, 10000, 12, "manual")
        val d = draft(); service.confirm(d.id)
        val e = service.updateEntry(d.id, service.today().minusDays(2), 47000.0, 70000, null, "수정 메모", emptySet())!!
        assertEquals(Source.USER, e.odometer!!.source)
        assertEquals(47200.0, e.odometer.observed!!.value, 0.0)
        assertEquals(70000L, service.ledger(YearMonth.from(service.today())).total)
        assertEquals(57000.0, service.schedules(47200.0).single().nextKm!!, 0.0)
        assertEquals(2, e.version)
        assertEquals(1, e.history.size)
    }

    @Test fun amountAndMemoRoundTripAndSelectiveReport() {
        service.updateProfile("EV", Powertrain.EV, null)
        val d = draft(item = Item.CHARGE)
        service.updateEntry(d.id, d.performedOn, 47200.0, 12000, 24.5, "개인 메모", emptySet())
        service.confirm(d.id)
        val e = service.entry(d.id)!!
        assertEquals(e, Codec.entry(Codec.entry(e)))
        val report = service.report(d.performedOn, d.performedOn, setOf(Item.CHARGE), false)
        assertTrue(report.contains("24.5 kWh")); assertFalse(report.contains("개인 메모"))
        assertFalse(service.report(d.performedOn, d.performedOn, emptySet(), true).contains("충전 ·"))
        assertTrue(service.report(d.performedOn, d.performedOn, null, true).contains("개인 메모"))
    }

    @Test fun oldJsonReadsWithDefaults() {
        val json = Codec.entry(draft())
        json.remove("updatedAt"); json.remove("energyAmount"); json.remove("detachedObservation")
        val e = Codec.entry(json)
        assertEquals(e.createdAt, e.updatedAt); assertNull(e.energyAmount)
        assertNull(e.engineOil)
    }

    @Test fun engineOilDetailsPersistAndCanBeEditedOrCleared() {
        val d = draft()
        fun update(details: EngineOilDetails?) =
            service.updateEntry(d.id, d.performedOn, 47200.0, d.cost, null, null, emptySet(), details)

        update(EngineOilDetails(" 테스트 제조사 ", 4.2))
        assertTrue(service.confirm(d.id) is DiaryService.ConfirmResult.Saved)
        val saved = Codec.entry(Codec.entry(service.entry(d.id)!!))
        assertEquals(EngineOilDetails("테스트 제조사", 4.2), saved.engineOil)
        assertNull(saved.energyAmount)
        assertThrows(IllegalArgumentException::class.java) { update(EngineOilDetails("제조사", 0.0)) }
        assertEquals(saved, service.entry(d.id))

        val edited = update(EngineOilDetails("다른 제조사", 4.5))!!
        assertEquals(EngineOilDetails("다른 제조사", 4.5), edited.engineOil)
        assertTrue(edited.history.last().summary.contains("엔진오일 사용량 변경"))
        assertNull(update(EngineOilDetails("", null))!!.engineOil)
    }

    @Test fun sharingFiltersTimelineLedgerScheduleReportAndMutation() {
        service.selectUser("A")
        val d = draft(); service.confirm(d.id)
        service.setPlan(Item.ENGINE_OIL, 10000, 12, "manual")
        service.setSharing("A", emptySet())
        service.selectUser("B")
        assertTrue(service.timeline().isEmpty()); assertNull(service.entry(d.id))
        assertEquals(0L, service.ledger(YearMonth.from(service.today())).total)
        assertEquals(ScheduleState.NO_RECORD, service.schedules(47200.0).single().state)
        assertFalse(service.report(d.performedOn, d.performedOn, null, true).contains("엔진오일 교환"))
        assertTrue(runCatching { service.setCost(d.id, 1) }.isFailure)
        assertTrue(runCatching { service.cancel(d.id) }.isFailure)
        service.selectUser("A"); service.setSharing("A", setOf("B")); service.selectUser("B")
        assertEquals(1, service.timeline().size)
        assertFalse(service.canEdit(service.entry(d.id)!!))
    }

    @Test fun multipleCandidatesRequireExplicitChoice() {
        repeat(2) { i -> store.saveCandidate(DetectionCandidate("c$i", service.profile().id, EnergyKind.FUEL,
            Observation(20.0, at.minusSeconds(60)), Observation(80.0, at), CandidateState.PENDING, false)) }
        val result = service.handleVoice("오늘 주유했어. 5만 원", Reading.Unavailable("test"), "A", null) as DiaryService.VoiceOutcome.Drafted
        assertNull(result.draft.candidateId)
        assertTrue(result.checks.any { it.contains("여러 개") })
    }

    @Test fun invalidEditsDoNotPartiallySave() {
        val d = draft()
        assertTrue(runCatching { service.updateEntry(d.id, d.performedOn, 1.0, -1, null, "bad", emptySet()) }.isFailure)
        assertEquals(d, service.entry(d.id))
        assertTrue(runCatching { service.updateEntry(d.id, d.performedOn, Double.NaN, 1, null, null, emptySet()) }.isFailure)
        assertTrue(runCatching { service.setDate(d.id, d.performedOn.plusDays(1)) }.isFailure)
    }

    @Test fun futureAndNegativeVehicleValuesAreUnavailable() {
        assertNull(service.currentKm(Reading.Value(Observation(-1.0, at))))
        assertNull(service.currentKm(Reading.Value(Observation(1.0, at.plusSeconds(60)))))
    }

    @Test fun dateChangeKeepsReferenceButDetachesCandidate() {
        val c = DetectionCandidate("c", service.profile().id, EnergyKind.FUEL, Observation(20.0, at.minusSeconds(60)), Observation(80.0, at), CandidateState.PENDING, false)
        store.saveCandidate(c)
        val d = service.draftFromCandidate(c.id, "A", Reading.Value(Observation(47200.0, at)))!!
        service.setDate(d.id, d.performedOn.minusDays(1))
        val updated = service.entry(d.id)!!
        assertNull(updated.odometer); assertNull(updated.candidateId)
        assertEquals(47200.0, updated.detachedObservation!!.value, 0.0)
    }

    @Test fun clearingThenReenteringKmKeepsVehicleEvidence() {
        val d = draft()
        service.setOdometer(d.id, null)
        service.setOdometer(d.id, 47100.0)
        assertEquals(47200.0, service.entry(d.id)!!.odometer!!.observed!!.value, 0.0)
    }

    @Test fun candidateOpenedTwiceReusesDraftAndYesterdayNeverGetsTodaysKm() {
        val midnight = Instant.parse("2026-11-13T15:01:00Z")
        val app = DiaryService(store, Clock.fixed(midnight, ZoneOffset.ofHours(9)))
        val beforeMidnight = midnight.minusSeconds(120)
        store.saveCandidate(DetectionCandidate("midnight", app.profile().id, EnergyKind.FUEL,
            Observation(20.0, beforeMidnight.minusSeconds(60)), Observation(80.0, beforeMidnight), CandidateState.PENDING, false))
        val d = app.draftFromCandidate("midnight", "A", Reading.Value(Observation(47200.0, midnight)))!!
        assertNull(d.odometer)
        assertEquals(d.id, app.draftFromCandidate("midnight", "A", Reading.Unavailable("test"))!!.id)
        assertEquals(1, app.drafts().size)
    }
}
