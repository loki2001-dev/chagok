package io.chagok.app.domain

import io.chagok.app.companion.EnergyConversation
import io.chagok.app.data.Codec
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AutoDiaryTest {
    class Time(var at: Instant=Instant.parse("2026-11-14T01:00:00Z")): Clock() {
        override fun instant()=at
        override fun getZone(): ZoneId=ZoneOffset.UTC
        override fun withZone(zone: ZoneId)=this
        fun step(seconds: Long) { at=at.plusSeconds(seconds) }
    }
    private val time=Time()
    private val store=MemoryStore()
    private var diary=DiaryService(store,time)
    private fun ev() { diary.updateProfile("EV",Powertrain.EV,null) }
    private fun feed(value: Double,seconds: Long=15,parked: Boolean=true): DetectionCandidate? {
        val r=diary.observeEnergy(EnergyKind.SOC,value,parked);time.step(seconds);return r
    }
    private fun stable(value: Double) { feed(value);feed(value) }
    private fun candidate(id:String="c",user:String="나"):EnergyConversation {
        ev();store.saveCandidate(DetectionCandidate(id,diary.profile().id,EnergyKind.SOC,Observation(30.0,time.at.minusSeconds(60)),Observation(80.0,time.at),CandidateState.PENDING,false))
        return EnergyConversation(diary).also { assertTrue(it.begin(id,user,Reading.Unavailable("test"))) }
    }
    @Test fun gradualChargingCreatesOneSessionFromThirtyToEighty() {
        ev();stable(30.0)
        for(percent in 31..80)repeat(12) { feed(percent.toDouble(),5) }
        val candidates=diary.pendingCandidates();assertEquals(1,candidates.size)
        assertEquals(30.0,candidates.single().before.value,0.01);assertEquals(80.0,candidates.single().after.value,0.01)
        assertTrue(candidates.single().collecting)
        feed(80.0,parked=false)
        assertFalse(diary.pendingCandidates().single().collecting)
        assertTrue(diary.confirmedEntries().isEmpty())
    }
    @Test fun restartKeepsAnchorAndSessionId() {
        ev();stable(30.0);stable(34.0);diary=DiaryService(store,time);stable(36.0)
        val id=diary.pendingCandidates().single().id
        diary=DiaryService(store,time);stable(40.0)
        assertEquals(id,diary.pendingCandidates().single().id)
        assertEquals(30.0,diary.pendingCandidates().single().before.value,0.01)
    }
    @Test fun slowOnePercentEveryTenMinutesStaysOneSession() {
        ev();stable(30.0)
        for(percent in 31..42)repeat(120) { feed(percent.toDouble(),5) }
        assertEquals(1,diary.pendingCandidates().size)
        assertEquals(42.0,diary.pendingCandidates().single().after.value,0.01)
    }
    @Test fun confirmedSessionDoesNotBillAgainAsChargeRises() {
        ev();stable(30.0);stable(40.0)
        val c=diary.pendingCandidates().single();val d=diary.draftFromCandidate(c.id,"나",Reading.Unavailable("test"))!!
        assertTrue(diary.confirm(d.id) is DiaryService.ConfirmResult.Saved)
        stable(60.0);stable(80.0)
        assertEquals(1,store.candidates(diary.profile().id).size);assertTrue(diary.pendingCandidates().isEmpty())
    }
    @Test fun stableNoiseDoesNotAccumulateIntoCharging() {
        ev();stable(50.0);repeat(20) { stable(if(it%2==0)51.0 else 50.0) }
        assertTrue(diary.pendingCandidates().isEmpty())
    }
    @Test fun observationGapIsMarkedAndAmountRemainsUnknown() {
        ev();stable(30.0);time.step(8*3600);diary=DiaryService(store,time);stable(80.0)
        val c=diary.pendingCandidates().single();assertTrue(c.gapLong)
        val d=diary.draftFromCandidate(c.id,"나",Reading.Unavailable("test"))!!
        assertNull(d.cost);assertNull(d.energyAmount);assertNull(d.odometer)
    }
    @Test fun missingCostAndFreeAreDifferentAndBothRequireConfirmation() {
        val c=candidate();c.receive("금액 몰라");assertTrue(diary.confirmedEntries().isEmpty());c.receive("맞아")
        assertNull(diary.confirmedEntries().single().cost)
        val otherStore=MemoryStore();val other=DiaryService(otherStore,time);other.updateProfile("EV",Powertrain.EV,null)
        otherStore.saveCandidate(store.candidates(diary.profile().id).single().copy(id="free",vehicleId=other.profile().id,state=CandidateState.PENDING,linkedEntryId=null))
        val free=EnergyConversation(other);free.begin("free","나",Reading.Unavailable("test"));free.receive("무료야");free.receive("네")
        assertEquals(0L,other.confirmedEntries().single().cost)
    }
    @Test fun rejectionClearsDraftAndPendingButNeverCreatesExpense() {
        val c=candidate();c.receive("충전한 거 아니야")
        assertTrue(diary.pendingCandidates().isEmpty());assertTrue(diary.drafts().isEmpty());assertTrue(diary.confirmedEntries().isEmpty())
    }
    @Test fun mutedTodaySurvivesRecreationButResetsTomorrow() {
        val c=candidate();c.receive("오늘은 묻지 마")
        assertTrue(DiaryService(store,time).questionQueue("나").muted())
        assertFalse(diary.questionQueue("가족").muted())
        time.step(86400);assertFalse(diary.questionQueue("나").muted())
    }
    @Test fun deferredQuestionWaitsForNextParkingAndResumesAmount() {
        val c=candidate();c.receive("12000원");c.receive("나중에")
        val q=diary.questionQueue("나");time.step(90)
        assertNull(q.next(diary.pendingCandidates(),true))
        store.saveSetting("monitor.parkSequence","1")
        assertNotNull(q.next(diary.pendingCandidates(),true));assertNull(q.next(diary.pendingCandidates(),false))
        val resumed=EnergyConversation(DiaryService(store,time));resumed.begin("c","나",Reading.Unavailable("test"))
        assertEquals(EnergyConversation.Stage.CONFIRM,resumed.stage);assertEquals(1,diary.drafts().size)
    }
    @Test fun unknownCostDecisionSurvivesClosingTheScreen() {
        val c=candidate();c.receive("금액은 모르겠어");c.defer()
        val resumed=EnergyConversation(DiaryService(store,time));resumed.begin("c","나",Reading.Unavailable("test"))
        assertEquals(EnergyConversation.Stage.CONFIRM,resumed.stage)
        resumed.receive("맞아");assertNull(diary.confirmedEntries().single().cost)
    }
    @Test fun quietChargingEndsAndAnotherTripSeparatesLaterCharging() {
        ev();stable(30.0);stable(50.0)
        repeat(365) { feed(50.0,5) }
        assertFalse(diary.pendingCandidates().single().collecting)
        feed(49.0,parked=false);stable(40.0);stable(60.0)
        assertEquals(2,diary.pendingCandidates().size)
    }
    @Test fun automaticQuestionsDoNotRunThroughAQueueAtOneParking() {
        candidate("first")
        val first=store.candidates(diary.profile().id).single()
        store.saveCandidate(first.copy(id="second"))
        val q=diary.questionQueue("나");time.step(120)
        assertNull(q.next(diary.pendingCandidates(),true))
        store.saveSetting("monitor.parkSequence","1")
        assertNotNull(q.next(diary.pendingCandidates(),true))
    }
    private fun sample(r:TripRecorder,speed:Float?,parked:Boolean?,km:Double?)=r.sample("v","나",time.at,speed,parked,km?.let { Observation(it,time.at) })
    @Test fun redLightsAndShortParkingRemainOneTrip() {
        val r=TripRecorder(store,10);sample(r,10f,false,100.0)
        repeat(5) { time.step(1);sample(r,10f,false,100.1) }
        repeat(20) { time.step(1);sample(r,0f,false,100.1) }
        repeat(5) { time.step(1);sample(r,0f,true,100.1) }
        time.step(1);sample(r,10f,false,100.2)
        repeat(11) { time.step(1);sample(r,0f,true,100.2) }
        val t=store.trips("v").single();assertNotNull(t.endedAt);assertEquals(0.2,t.distanceKm!!,0.001);assertEquals(5,t.movingSeconds)
    }
    @Test fun missingSamplesDoNotInflateMovingTimeAndRestartContinues() {
        var r=TripRecorder(store);sample(r,10f,false,100.0)
        time.step(5);sample(r,10f,false,100.1)
        time.step(120);r=TripRecorder(store);sample(r,10f,false,101.0)
        val t=store.trips("v").single();assertTrue(t.hasGap);assertEquals(5,t.movingSeconds)
    }
    @Test fun longShutdownClosesOldTripAndCreatesAnother() {
        sample(TripRecorder(store),10f,false,100.0);time.step(3600)
        sample(TripRecorder(store),10f,false,105.0)
        assertEquals(2,store.trips("v").size);assertEquals(1,store.trips("v").count { it.endedAt!=null })
    }
    @Test fun invalidOdometerCannotMakeNegativeOrHugeTrip() {
        val r=TripRecorder(store);sample(r,10f,false,100.0);time.step(5);sample(r,10f,false,50.0)
        var t=store.trips("v").single();assertTrue(t.hasGap);assertEquals(0.0,t.distanceKm!!,0.001)
        time.step(5);sample(r,10f,false,10000.0);t=store.trips("v").single();assertEquals(0.0,t.distanceKm!!,0.001)
    }
    @Test fun highwayDistanceUsesOdometerIntervalInsteadOfSpeedSampleInterval() {
        val r=TripRecorder(store);val initial=Observation(100.0,time.at)
        r.sample("v","나",time.at,50f,false,initial)
        repeat(29) { time.step(1);r.sample("v","나",time.at,50f,false,initial) }
        time.step(1);sample(r,50f,false,101.5)
        val trip=store.trips("v").single()
        assertEquals(1.5,trip.distanceKm!!,0.001);assertFalse(trip.hasGap)
        assertEquals(trip,Codec.trip(Codec.trip(trip)))
    }
    @Test fun checkpointsAndFriendsRoundTripWithoutLosingState() {
        val r=TripRecorder(store);sample(r,10f,false,100.0)
        var t=store.trips("v").single();store.saveTrip(t.copy(friends=setOf("calico")))
        time.step(5);sample(r,10f,false,100.1);t=store.trips("v").single()
        assertEquals(setOf("calico"),t.friends);assertEquals(t,Codec.trip(Codec.trip(t)))
        val e=EnergyCheckpoint(Observation(80.0,time.at),Observation(30.0,time.at.minusSeconds(3600)),time.at,time.at,true)
        assertEquals(e,Codec.energyCheckpoint(Codec.energyCheckpoint(e)))
    }
}
