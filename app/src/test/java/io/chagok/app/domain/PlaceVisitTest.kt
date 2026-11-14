package io.chagok.app.domain

import io.chagok.app.data.Codec
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PlaceVisitTest {
    private val now = Instant.parse("2026-11-14T03:00:00Z")
    private val place = ServicePlace("r1", "테스트 정비소", PlaceKind.REPAIR, 37.5, 127.0, "테스트 주소", "fixture")
    private val store = MemoryStore().apply { replacePlaces(listOf(place)) }
    private var tracker = PlaceVisitTracker(store)
    private fun sample(seconds: Long, parked: Boolean? = true, lat: Double = 37.5, fixAt: Long = seconds) {
        tracker.sample("car", "나", PlaceFix(lat,127.0,now.plusSeconds(fixAt)),now.plusSeconds(seconds),parked,Observation(1000.0,now))
    }
    private fun only() = store.visits("car").single()
    @Test fun `continuous parked fixes produce one question and never confirm`() {
        (0L..60L step 10).forEach { sample(it) }
        assertEquals(VisitState.READY,only().state)
        assertEquals(60L,only().observedSeconds)
        sample(70); assertEquals(1,store.visits("car").size)
        assertTrue(store.entries("car").isEmpty())
    }
    @Test fun `drive by and unknown gear do not create arrival`() {
        sample(0,false); sample(10,null); assertTrue(store.visits("car").isEmpty())
    }
    @Test fun `short stop exits without question`() {
        sample(0); sample(10); sample(20,false,37.51)
        assertEquals(VisitState.EXCLUDED,only().state); assertTrue(only().exited)
    }
    @Test fun `stale future invalid and cached fixes cannot accumulate dwell`() {
        sample(0); sample(10,fixAt=0); sample(40,fixAt=0); sample(50,fixAt=100)
        sample(60,lat=Double.NaN); assertEquals(0L,only().observedSeconds)
        assertEquals(VisitState.WATCHING,only().state)
    }
    @Test fun `extreme timestamp is rejected without overflowing duration`() {
        assertFalse(PlaceFix(37.5,127.0,Instant.MIN).fresh(now))
        assertFalse(PlaceFix(37.5,127.0,Instant.MAX).fresh(now))
    }
    @Test fun `unknown motion between fixes breaks observed dwell`() {
        sample(0); sample(5,null,fixAt=0); sample(10)
        assertEquals(0L,only().observedSeconds)
    }
    @Test fun `restart recovers arrival with uncertainty not invented dwell`() {
        sample(0); tracker=PlaceVisitTracker(store); sample(200)
        assertEquals(VisitState.READY,only().state); assertTrue(only().hasGap)
        assertEquals(0L,only().observedSeconds)
    }
    @Test fun `overlapping places do not choose arbitrary shop`() {
        store.replacePlaces(listOf(place,place.copy(id="r2")))
        sample(0); sample(60); assertTrue(store.visits("car").isEmpty())
    }
    @Test fun `resolved visit only rearms after leaving exit radius`() {
        sample(0); store.saveVisit(only().copy(state=VisitState.EXCLUDED))
        sample(10); assertEquals(1,store.visits("car").size)
        sample(20,false,37.51); sample(30)
        assertEquals(2,store.visits("car").size)
    }
    @Test fun `electric vehicles do not get fuel station questions`() {
        store.replacePlaces(listOf(place.copy(kind=PlaceKind.FUEL)))
        tracker.sample("car","나",PlaceFix(37.5,127.0,now),now,true,null,fuelAllowed=false)
        assertTrue(store.visits("car").isEmpty())
        val service=DiaryService(store,Clock.fixed(now,ZoneOffset.UTC));service.selectUser("나")
        service.updateProfile("전기차",Powertrain.EV,null)
        store.saveVisit(PlaceVisit("fuel",service.profile().id,"나",place.copy(kind=PlaceKind.FUEL),now,now,null,VisitState.READY))
        assertTrue(service.pendingVisits("나").isEmpty())
    }
    @Test fun `visits belong to the active user`() {
        sample(0)
        tracker.sample("car","가족",PlaceFix(37.5,127.0,now.plusSeconds(10)),now.plusSeconds(10),true,null)
        assertEquals(setOf("나","가족"),store.visits("car").map { it.user }.toSet())
    }
    @Test fun `visit and place survive codec round trip`() {
        sample(0); assertEquals(only(),Codec.visit(Codec.visit(only())))
        assertEquals(place,Codec.place(Codec.place(place)))
    }
    @Test fun `draft uses original arrival and confirms only after review`() {
        val service=DiaryService(store,Clock.fixed(now.plusSeconds(86400),ZoneOffset.UTC)); service.selectUser("나")
        val visit=PlaceVisit("v",service.profile().id,"나",place,now,now,Observation(1000.0,now),VisitState.READY)
        store.saveVisit(visit)
        val draft=service.visitDraft("v","나",Item.ENGINE_OIL)
        assertEquals(LocalDate.parse("2026-11-14"),draft.performedOn)
        assertEquals(1000.0,draft.odometer!!.value,0.0)
        assertEquals(draft,service.visitDraft("v","나",Item.ENGINE_OIL))
        assertEquals(1,service.reviewCount()); assertEquals(VisitState.READY,store.visits(visit.vehicleId).single().state)
        assertTrue(service.confirm(draft.id) is DiaryService.ConfirmResult.Saved)
        assertEquals(VisitState.CONFIRMED,store.visits(visit.vehicleId).single().state)
        assertTrue(service.confirm(draft.id) is DiaryService.ConfirmResult.Rejected)
        service.cancel(draft.id); assertEquals(VisitState.READY,store.visits(visit.vehicleId).single().state)
        assertEquals(1,service.pendingVisits("나").size)
    }
    @Test fun `discarded draft returns to inbox and rejection stays excluded`() {
        val service=DiaryService(store,Clock.fixed(now,ZoneOffset.UTC)); service.selectUser("나")
        val v=PlaceVisit("v",service.profile().id,"나",place,now,now,null,VisitState.READY);store.saveVisit(v)
        val d=service.visitDraft("v","나",Item.ENGINE_OIL)
        assertTrue(runCatching { service.rejectVisit("v","나") }.isFailure)
        service.cancel(d.id); service.rejectVisit("v","나");assertTrue(service.pendingVisits("나").isEmpty())
        assertTrue(runCatching { service.visitDraft("v","나",Item.ENGINE_OIL) }.isFailure)
    }
    @Test fun `other user cannot draft or reject private visit`() {
        val service=DiaryService(store,Clock.fixed(now,ZoneOffset.UTC)); service.selectUser("나")
        store.saveVisit(PlaceVisit("v",service.profile().id,"가족",place,now,now,null,VisitState.READY))
        assertTrue(service.pendingVisits("나").isEmpty())
        assertTrue(runCatching { service.visitDraft("v","나",Item.ENGINE_OIL) }.isFailure)
        assertTrue(runCatching { service.rejectVisit("v","가족") }.isFailure)
    }
    @Test fun `fuel visit and unique energy candidate share a single diary draft`() {
        val service=DiaryService(store,Clock.fixed(now,ZoneOffset.UTC)); service.selectUser("나")
        val car=service.profile().id
        store.saveVisit(PlaceVisit("v",car,"나",place.copy(kind=PlaceKind.FUEL),now,now,null,VisitState.READY))
        store.saveCandidate(DetectionCandidate("energy",car,EnergyKind.FUEL,Observation(10.0,now.minusSeconds(60)),Observation(80.0,now),CandidateState.PENDING,false))
        val draft=service.visitDraft("v","나",Item.REFUEL)
        assertEquals("energy",draft.candidateId); assertEquals(1,service.reviewCount())
        assertTrue(service.confirm(draft.id) is DiaryService.ConfirmResult.Saved)
        assertEquals(CandidateState.CONFIRMED,store.candidates(car).single().state)
    }
}
