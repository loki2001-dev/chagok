package io.chagok.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PlaceVisitRegressionTest {
    private val t=Instant.parse("2026-11-15T00:00:00Z")
    private val p=ServicePlace("shop","정비소",PlaceKind.REPAIR,37.5,127.0,"주소","fixture")
    private val s=MemoryStore().apply { replacePlaces(listOf(p)) }
    private val tracker=PlaceVisitTracker(s)
    private fun sample(seconds:Long,lat:Double=37.5,parked:Boolean?=true) {
        val at=t.plusSeconds(seconds); tracker.sample("car","나",PlaceFix(lat,127.0,at),at,parked,null)
    }
    @Test fun deferredVisitShouldNotDiscardNextDayVisit() {
        (0L..60L step 10).forEach { sample(it) }
        assertEquals(VisitState.READY,s.visits("car").single().state)
        sample(70,37.51,false)
        assertTrue(s.visits("car").single().exited)
        (86400L..86460L step 10).forEach { sample(it) }
        assertEquals("new visit after observed departure",2,s.visits("car").size)
    }
    @Test fun subsecondPrecisionMustNotLoseAllObservedDwell() {
        (0L..70L).forEach { n ->
            val now=t.plusMillis(n*1000)
            val at=now.minusMillis(n%2) // actual one-second monitor ticks; location timestamp has 1 ms jitter
            tracker.sample("car","나",PlaceFix(37.5,127.0,at),now,true,null)
        }
        assertTrue("70 seconds observed with 1 ms timestamp jitter",s.visits("car").single().observedSeconds >= 60)
    }
    @Test fun knownDrivingIsNotRecoveryFromMissingObservations() {
        sample(0)
        (10L..120L step 10).forEach { sample(it,37.5007,false) }
        sample(130)
        assertEquals("only one zero-duration stop on each end; motion was known in between",VisitState.WATCHING,s.visits("car").single().state)
    }
    @Test fun energyDraftBeforeVisitShouldStillBeOneReviewTask() {
        val d=DiaryService(s,Clock.fixed(t,ZoneOffset.UTC));d.selectUser("나")
        val car=d.profile().id
        s.saveCandidate(DetectionCandidate("energy",car,EnergyKind.FUEL,Observation(10.0,t.minusSeconds(60)),Observation(80.0,t),CandidateState.PENDING,false))
        val energyDraft=d.draftFromCandidate("energy","나",Reading.Unavailable("test"))!!
        s.saveVisit(PlaceVisit("visit",car,"나",p.copy(kind=PlaceKind.FUEL),t,t,null,VisitState.READY))
        val visitDraft=d.visitDraft("visit","나",Item.REFUEL)
        assertEquals("same fueling event",energyDraft.id,visitDraft.id)
    }

    @Test fun persistedDrivingMustNotBecomeRecoveryAfterRestart() {
        sample(0); sample(10,37.5007,false)
        val restarted=PlaceVisitTracker(s)
        restarted.sample("car","나",PlaceFix(37.5,127.0,t.plusSeconds(200)),t.plusSeconds(200),true,null)
        assertEquals(VisitState.WATCHING,s.visits("car").single().state)
        assertEquals(0L,s.visits("car").single().observedMillis)
    }
    @Test fun knownDrivingWithCachedFixAlsoBlocksRecovery() {
        sample(0)
        tracker.sample("car","나",PlaceFix(37.5,127.0,t),t.plusSeconds(5),false,null)
        PlaceVisitTracker(s).sample("car","나",PlaceFix(37.5,127.0,t.plusSeconds(200)),t.plusSeconds(200),true,null)
        assertEquals(VisitState.WATCHING,s.visits("car").single().state)
    }
    @Test fun fractionalDwellAndLastObservationSurviveStorage() {
        sample(0)
        val at=t.plusMillis(999)
        tracker.sample("car","나",PlaceFix(37.5,127.0,at),at,true,null)
        val visit=s.visits("car").single()
        val decoded=io.chagok.app.data.Codec.visit(io.chagok.app.data.Codec.visit(visit))
        assertEquals(999L,decoded.observedMillis)
        assertEquals(at,decoded.lastObservedAt)
        assertTrue(decoded.lastMatchedParked)
        val legacy=io.chagok.app.data.Codec.visit(visit.copy(observedSeconds=5))
        listOf("millis","lastObserved","matchedParked").forEach { legacy.remove(it) }
        val old=io.chagok.app.data.Codec.visit(legacy)
        assertEquals(5000L,old.observedMillis)
        assertEquals(old.lastAt,old.lastObservedAt)
    }
    @Test fun mergedFuelDraftPreservesUserEditsAndIsIdempotent() {
        val d=DiaryService(s,Clock.fixed(t,ZoneOffset.UTC));d.selectUser("나")
        val car=d.profile().id
        s.saveCandidate(DetectionCandidate("energy",car,EnergyKind.FUEL,Observation(10.0,t.minusSeconds(60)),Observation(80.0,t),CandidateState.PENDING,false))
        val original=d.draftFromCandidate("energy","나",Reading.Unavailable("test"))!!
            .copy(cost=50000,memo="직접 쓴 메모",energyAmount=30.0)
        s.saveEntry(original)
        s.saveVisit(PlaceVisit("visit",car,"나",p.copy(kind=PlaceKind.FUEL),t,t,null,VisitState.READY))
        val merged=d.visitDraft("visit","나",Item.REFUEL)
        assertEquals(original.copy(visitId="visit",placeName=p.name),merged)
        assertEquals(merged,d.visitDraft("visit","나",Item.REFUEL))
        assertEquals(1,d.reviewCount())
        assertTrue(d.confirm(merged.id) is DiaryService.ConfirmResult.Saved)
        assertEquals(VisitState.CONFIRMED,s.visits(car).single().state)
        assertEquals(CandidateState.CONFIRMED,s.candidates(car).single().state)
    }
    @Test fun cannotMergeAnotherUsersPrivateDraft() {
        val d=DiaryService(s,Clock.fixed(t,ZoneOffset.UTC));d.selectUser("가족")
        val car=d.profile().id
        s.saveCandidate(DetectionCandidate("energy",car,EnergyKind.FUEL,Observation(10.0,t.minusSeconds(60)),Observation(80.0,t),CandidateState.PENDING,false))
        val other=d.draftFromCandidate("energy","가족",Reading.Unavailable("test"))!!
        d.selectUser("나")
        s.saveVisit(PlaceVisit("visit",car,"나",p.copy(kind=PlaceKind.FUEL),t,t,null,VisitState.READY))
        val mine=d.visitDraft("visit","나",Item.REFUEL)
        assertNotEquals(other.id,mine.id); assertNull(mine.candidateId)
        assertEquals(other,s.entry(other.id))
    }
    @Test fun anotherVisitCannotStealAnAlreadyLinkedFuelDraft() {
        val d=DiaryService(s,Clock.fixed(t,ZoneOffset.UTC));d.selectUser("나")
        val car=d.profile().id
        s.saveCandidate(DetectionCandidate("energy",car,EnergyKind.FUEL,Observation(10.0,t.minusSeconds(60)),Observation(80.0,t),CandidateState.PENDING,false))
        listOf("visit1","visit2").forEach { s.saveVisit(PlaceVisit(it,car,"나",p.copy(kind=PlaceKind.FUEL),t,t,null,VisitState.READY)) }
        val first=d.visitDraft("visit1","나",Item.REFUEL)
        val second=d.visitDraft("visit2","나",Item.REFUEL)
        assertNotEquals(first.id,second.id);assertNull(second.candidateId)
        assertEquals("visit1",s.entry(first.id)!!.visitId)
    }
}
