package io.chagok.app.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionModelTest {
    private val modes = listOf("OFF", "CONTINUOUS_LEVEL_1", "CONTINUOUS_LEVEL_3", "INTERMITTENT_LEVEL_1", "INTERMITTENT_LEVEL_2", "AUTO")
    private fun samples(p: WiperRainPolicy, from: Long, to: Long, mode: String, on: Boolean) {
        for (t in from..to step 1000) { p.sample(t, mode, on, modes); p.tick(t) }
    }
    @Test fun rainRequiresFullMinuteThenTracksSupportedStepsAndClearsAfterThirtySeconds() {
        val p = WiperRainPolicy()
        samples(p, 0, 59000, modes[1], true); assertFalse(p.confirmed); assertEquals(0f,p.intensity,0f)
        samples(p, 60000, 63000, modes[1], true); assertTrue(p.confirmed); assertEquals(.45f,p.intensity,.001f)
        samples(p, 64000, 68000, modes[2], true); assertEquals(1f,p.intensity,.001f)
        samples(p, 69000, 98000, "OFF", false); assertTrue(p.confirmed)
        samples(p, 99000, 106000, "OFF", false); assertFalse(p.confirmed); assertEquals(0f,p.intensity,.001f)
    }
    @Test fun staleAndMissingSamplesRestartFullMinuteWithoutInventingRain() {
        val p=WiperRainPolicy();samples(p,0,58000,modes[1],true)
        samples(p,65000,124000,modes[1],true);assertFalse(p.confirmed)
        p.sample(125000,modes[1],true,modes);assertTrue(p.confirmed)
        p.sample(126000,null,null,modes);assertFalse(p.confirmed)
        samples(p,127000,186000,modes[1],true);assertFalse(p.confirmed)
        p.sample(187000,modes[1],true,modes);assertTrue(p.confirmed)
        p.tick(193000);assertFalse(p.confirmed)
    }
    @Test fun autoOffAndMistDoNotConfirmButObservedIntermittentSweepsDo() {
        for (mode in listOf("AUTO","MIST","SERVICE","OFF","OTHER")) {
            val p=WiperRainPolicy();samples(p,0,70000,mode,mode!="AUTO");assertFalse(mode,p.confirmed)
        }
        val p=WiperRainPolicy();samples(p,0,70000,"INTERMITTENT_LEVEL_1",false);assertFalse(p.confirmed)
        p.sample(71000,"INTERMITTENT_LEVEL_1",true,modes)
        samples(p,72000,131000,"INTERMITTENT_LEVEL_1",false);assertTrue(p.confirmed)
        samples(p,132000,163000,"AUTO",false);assertFalse(p.confirmed)
    }
    @Test fun oneSupportedStepAndUnknownModesStayBounded() {
        assertEquals(.725f,WiperRainPolicy.strength(modes[1],listOf(modes[1])),.001f)
        assertEquals(.55f,WiperRainPolicy.strength("AUTO",modes),0f)
        assertTrue(WiperRainPolicy.strength("INTERMITTENT_LEVEL_1",modes)<.45f)
    }
    @Test fun speedRejectsInvalidAndStaleDataAndSmoothsAcceleration() {
        val f=SpeedFilter();f.accept(0,0f);f.accept(200,1f)
        assertEquals(1f,f.speed(200)!!,0f);assertTrue(f.acceleration(200)>0)
        assertNull(f.speed(3201));assertEquals(0f,f.acceleration(3201),0f)
        f.accept(4000,-4f);assertEquals(4f,f.speed(4000)!!,0f);assertEquals(0f,f.acceleration(4000),0f)
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,151f,null)){f.accept(4200,value);assertNull(f.speed(4200))}
    }
    @Test fun encountersPreferCityAndUnseenAndNeverImmediatelyRepeat() {
        val city=EncounterDirector{.1};assertEquals(Scenery.CITY,city.scenery)
        val seen=mutableSetOf<String>()
        repeat(7){val e=city.choose(seen);assertEquals(Scenery.CITY,e.scenery);assertTrue(seen.add(e.id));city.lastId=e.id}
        assertNotEquals(city.lastId,city.choose(seen).id)
        val country=EncounterDirector{.9};repeat(3){val e=country.choose(seen);assertEquals(Scenery.COUNTRY,e.scenery);assertTrue(seen.add(e.id));country.lastId=e.id}
        assertEquals(10,seen.size)
    }
    @Test fun encountersAwardOnlyVisibleMovingAllowedPassOnceAndRetryFailedStorage() {
        val d=EncounterDirector{.1};d.begin(Encounters.all.first(),80f);var calls=0
        repeat(30){d.step(.1f,1f,true,false,false,emptySet()){calls++;true}}
        repeat(30){d.step(.1f,1f,false,true,false,emptySet()){calls++;true}}
        assertEquals(0,calls);assertEquals(80f,d.active!!.y,0f)
        repeat(14){d.step(.1f,1f,true,true,false,emptySet()){calls++;true}};assertEquals(0,calls)
        d.step(.2f,1f,true,true,false,emptySet()){calls++;false};assertEquals(1,calls)
        d.step(.1f,1f,true,true,false,emptySet()){calls++;true};assertEquals(2,calls)
        repeat(20){d.step(.1f,1f,true,true,false,emptySet()){calls++;true}};assertEquals(2,calls)
    }
    @Test fun questionsPostponeNewEncountersAndFinishedPassResetsCooldown() {
        val d=EncounterDirector{.1};d.remainingSeconds=1f
        d.step(2f,100f,true,true,true,emptySet()){true};assertNull(d.active)
        d.step(2f,100f,true,true,false,emptySet()){true};assertNotNull(d.active)
        d.step(10f,1000f,true,true,false,emptySet()){true};assertNull(d.active)
        assertTrue(d.remainingSeconds in 180f..420f)
    }
    @Test fun tallWindowAwardsAtTheVisibleCarAndResizeDoesNotAwardAgain() {
        val d=EncounterDirector{.1};d.viewportHeight=1000f;d.begin(Encounters.all.first(),80f)
        var calls=0
        repeat(30){d.step(.1f,5f,true,true,false,emptySet()){calls++;true}}
        assertEquals("Still above the car in a tall window",0,calls)
        repeat(40){d.step(.1f,5f,true,true,false,emptySet()){calls++;true}}
        assertEquals(1,calls)
        assertNotNull("Keep the encounter until it leaves the tall viewport",d.active)
        d.viewportHeight=500f
        repeat(30){d.step(.1f,5f,true,true,false,emptySet()){calls++;true}}
        assertEquals(1,calls);assertNull(d.active)
    }
}
