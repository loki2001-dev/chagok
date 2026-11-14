package io.chagok.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class SpecV42AuditTest {
    private class TestClock(var time: Instant = Instant.parse("2026-11-13T01:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.ofHours(9)
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = time
    }
    private val clock = TestClock()
    private val store = MemoryStore()
    private val service = DiaryService(store, clock)
    private fun draft(author: String = "A") = service.manualDraft(
        Item.ENGINE_OIL, service.today(), 80000,
        Reading.Value(Observation(47200.0, clock.instant())), author)
    private fun candidate(): DetectionCandidate {
        val now = clock.instant()
        val c = DetectionCandidate("candidate", service.profile().id, EnergyKind.FUEL,
            Observation(30.0, now.minusSeconds(30)), Observation(80.0, now), CandidateState.PENDING, false)
        store.saveCandidate(c)
        return c
    }

    @Test fun changingDateToPastMustNotKeepTodaysVehicleKm() {
        val d = draft()
        service.setDate(d.id, service.today().minusWeeks(1))
        val e = service.entry(d.id)!!
        assertTrue("Past date still carries today's automatically attached VEHICLE km", e.odometer == null || e.odometer.source != Source.VEHICLE)
    }

    @Test fun cancelledEvidenceMustReviewOtherUsersStampAfterServiceRestart() {
        service.registerUser("A"); service.registerUser("B")
        val d = draft(); service.confirm(d.id)
        val m = service.missions("B", 47200.0).first()
        assertTrue(service.answer("B", m, m.answerIndex).stamped)
        val restarted = DiaryService(store, clock)
        restarted.registerUser("A")
        restarted.cancel(d.id)
        assertTrue("B retains valid stamp backed by cancelled entry after restart", restarted.stamps("B").single().needsReview)
    }

    @Test fun pastRefuelMustNotAutoLinkTodaysCandidate() {
        candidate()
        val result = service.handleVoice("지난주 주유했어. 5만 원", Reading.Unavailable("test"), "A", null) as DiaryService.VoiceOutcome.Drafted
        assertNull("Past refuel auto-linked to today's candidate", result.draft.candidateId)
    }

    @Test fun excludedCandidateMustNotBeConfirmedByOldDraft() {
        val c = candidate()
        val d = service.draftFromCandidate(c.id, "A", Reading.Unavailable("test"))!!
        service.excludeCandidate(c.id)
        val result = service.confirm(d.id)
        assertTrue("Old draft revives excluded candidate without renewed confirmation", result is DiaryService.ConfirmResult.Rejected)
    }

    @Test fun newlyConfirmedOldDraftMustAppearInSharedChanges() {
        val d = draft("A")
        clock.time = clock.time.plusSeconds(60)
        service.markSeen("B")
        clock.time = clock.time.plusSeconds(60)
        service.confirm(d.id)
        assertTrue("New confirmation missing because changesSince uses draft creation time", service.changesSince("B").any { it.id == d.id })
    }

    @Test fun invalidPercentMustNotProduceCandidate() {
        service.observeEnergy(EnergyKind.FUEL, 30.0, true)
        clock.time = clock.time.plusSeconds(15)
        service.observeEnergy(EnergyKind.FUEL, 30.0, true)
        clock.time = clock.time.plusSeconds(15)
        service.observeEnergy(EnergyKind.FUEL, 130.0, true)
        clock.time = clock.time.plusSeconds(15)
        assertNull("Out of range percentage produced a candidate", service.observeEnergy(EnergyKind.FUEL, 130.0, true))
    }
}
