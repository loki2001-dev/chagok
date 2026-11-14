package io.chagok.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.data.*
import io.chagok.app.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PlaceVisitDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val fixture = ServicePlace("fixture", "시험용 정비소", PlaceKind.REPAIR, 37.5, 127.0, "합성 데이터", "test")

    @Test fun catalogueRollbackAndCrossConnectionInvalidation() {
        check(context.packageName.endsWith(".smoke"))
        val name = "places-test-${System.nanoTime()}.db"
        val a=SqliteStore(context,name); val b=SqliteStore(context,name)
        try {
            a.replacePlaces(listOf(fixture)); assertEquals(listOf(fixture),b.places())
            runCatching { a.transaction { a.replacePlaces(listOf(fixture.copy(id="bad"))); error("interrupted") } }
            assertEquals(listOf(fixture),a.places()); assertEquals(listOf(fixture),b.places())
            val next=fixture.copy(id="new"); a.replacePlaces(listOf(next))
            assertEquals(listOf(next),b.places())
            val visit=PlaceVisit("visit","car","나",fixture,Instant.now(),Instant.now(),null,VisitState.READY)
            a.saveVisit(visit); assertEquals(visit,b.visits("car").single())
        } finally { a.close(); b.close(); context.deleteDatabase(name) }
    }

    /** Opt-in real SDK probe. Expected coordinates must be injected into the emulator externally.
     * Uses a synthetic POI at the injected position; does not claim a real business dataset lookup.
     */
    @Test fun fusedLocationMatchesInjectedPosition() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("placeProbe") == "true")
        check(context.packageName.endsWith(".smoke"))
        val latitude=args.getString("latitude")!!.toDouble()
        val longitude=args.getString("longitude")!!.toDouble()
        val client=FusedLocation(context)
        val fix=AtomicReference<LocationData?>()
        val lastSeen=AtomicReference<LocationData?>()
        val error=AtomicReference<String?>()
        val ready=CountDownLatch(1)
        val report=JSONObject().put("supported",client.isSupported())
            .put("permission",context.checkSelfPermission("pleos.car.permission.FUSED_LOCATION"))
            .put("poiSource","synthetic fixture; not a real shop")
        val listener=object:FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                lastSeen.set(location)
                if(location != null && PlaceFix(location.latitude,location.longitude,Instant.ofEpochMilli(location.timestampMillis)).fresh(Instant.now()) &&
                    distanceMeters(latitude,longitude,location.latitude,location.longitude)<60) { fix.set(location);ready.countDown() }
            }
            override fun onError(exception: Exception) { error.set(exception.toString()) }
        }
        try {
            assertTrue("FusedLocation service missing",client.isSupported())
            assertEquals("FusedLocation permission missing",0,context.checkSelfPermission("pleos.car.permission.FUSED_LOCATION"))
            instrument.runOnMainSync { client.initialize();client.registerFusedLocationCallback(listener) }
            repeat(20) {
                if(fix.get() == null) {
                    instrument.runOnMainSync { client.getLastKnownLocation(listener) }
                    ready.await(1,TimeUnit.SECONDS)
                }
            }
            val location=fix.get()
            assertNotNull("No fresh matching fix: ${error.get()}",location)
            location!!
            report.put("latitude",location.latitude).put("longitude",location.longitude).put("timestampMillis",location.timestampMillis)
            val store=MemoryStore().apply { replacePlaces(listOf(fixture.copy(latitude=latitude,longitude=longitude))) }
            PlaceVisitTracker(store).sample("car","나",PlaceFix(location.latitude,location.longitude,Instant.ofEpochMilli(location.timestampMillis)),Instant.now(),true,null)
            assertEquals(1,store.visits("car").size)
            assertEquals(VisitState.WATCHING,store.visits("car").single().state)
            report.put("nearbyCandidate",true)
        } finally {
            instrument.runOnMainSync { runCatching { client.unregisterFusedLocationCallback(listener) };client.release() }
            lastSeen.get()?.let { report.put("lastLatitude",it.latitude).put("lastLongitude",it.longitude).put("lastTimestampMillis",it.timestampMillis) }
            report.put("checkedAt",Instant.now().toString())
            report.put("error",error.get() ?: JSONObject.NULL)
            File(context.filesDir,"place-sdk-probe.json").writeText(report.toString(2))
            instrument.sendStatus(0,Bundle().apply { putString("place_probe_report",report.toString()) })
        }
    }
}
