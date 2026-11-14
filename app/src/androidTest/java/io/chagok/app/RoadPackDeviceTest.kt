package io.chagok.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import android.os.SystemClock
import io.chagok.app.domain.PlaceFix
import io.chagok.app.domain.distanceMeters
import io.chagok.app.roads.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class RoadPackDeviceTest {
    @Test fun v5DiarySurvivesRoadStatisticsUpgrade() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".roadprobe") || context.packageName.endsWith(".smoke"))
        val name="road-upgrade-${java.util.UUID.randomUUID()}.db"
        val file=context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val profile=io.chagok.app.domain.VehicleProfile("existing-car","기존 일지",io.chagok.app.domain.Powertrain.ICE)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE doc (kind TEXT NOT NULL, id TEXT NOT NULL, owner TEXT, json TEXT NOT NULL, PRIMARY KEY(kind,id))")
            db.execSQL("CREATE INDEX doc_owner ON doc(kind,owner)")
            db.execSQL("INSERT INTO doc(kind,id,owner,json) VALUES(?,?,?,?)",arrayOf("profile","current",null,io.chagok.app.data.Codec.profile(profile).toString()))
            db.version=5
        }
        val store=io.chagok.app.data.SqliteStore(context,name)
        try {
            assertEquals(profile,store.profile())
            val at=Instant.parse("2026-11-15T00:00:00Z")
            RoadUsageRecorder(store).sample(profile.id,at,10f,true,null,null)
            assertEquals(1,store.roadUsage(profile.id).size)
            assertEquals(profile,store.profile())
        } finally { store.close();context.deleteDatabase(name) }
    }

    @Test fun roadStatisticsSurviveSqliteReopenWithoutReplay() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".roadprobe") || context.packageName.endsWith(".smoke"))
        val database="road-test-${java.util.UUID.randomUUID()}.db"
        var store=io.chagok.app.data.SqliteStore(context,database)
        val start=Instant.parse("2026-11-15T00:00:00Z")
        try {
            store.saveProfile(io.chagok.app.domain.VehicleProfile("installed-car","차곡",io.chagok.app.domain.Powertrain.ICE))
            var recorder=RoadUsageRecorder(store)
            for(second in 0L..2L) recorder.sample("installed-car",start.plusSeconds(second),10f,true,RoadGrade.MOTORWAY,
                io.chagok.app.domain.Observation(100.0+second*.01,start.plusSeconds(second)))
            store.close();store=io.chagok.app.data.SqliteStore(context,database);recorder=RoadUsageRecorder(store)
            recorder.sample("installed-car",start.plusSeconds(2),10f,true,RoadGrade.MOTORWAY,io.chagok.app.domain.Observation(100.02,start.plusSeconds(2)))
            recorder.sample("installed-car",start.plusSeconds(10),10f,true,RoadGrade.MOTORWAY,io.chagok.app.domain.Observation(100.10,start.plusSeconds(10)))
            val date=java.time.LocalDate.of(2026,11,15)
            val result=RoadUsageQuery.summary(store,date,date)
            assertEquals(.10,result.totalDistanceKm,1e-8)
            assertEquals(.09,result.buckets.getValue(RoadBucket.UNKNOWN).distanceKm,1e-8)
            assertEquals(8000L,result.observationGapMillis)
        } finally { store.close();context.deleteDatabase(database) }
    }

    @Test fun actualPleosFixQueriesBundledNationalRoadPack() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("roadProbe")=="true")
        val lat=args.getString("latitude")!!.toDouble();val lon=args.getString("longitude")!!.toDouble()
        val expected=args.getString("expected")!!
        val context=instrument.targetContext
        check(context.packageName.endsWith(".roadprobe"))
        val location=AtomicReference<LocationData?>()
        val sdk=FusedLocation(context)
        val listener=object:FusedLocationListener {
            override fun onChanged(value:LocationData?) {
                if(value!=null && RoadMatcher.fresh(PlaceFix(value.latitude,value.longitude,Instant.ofEpochMilli(value.timestampMillis)),Instant.now()) &&
                    distanceMeters(lat,lon,value.latitude,value.longitude)<5) location.set(value)
            }
            override fun onError(exception:Exception) { }
        }
        val file=File(context.cacheDir,"road-test.zip")
        try {
            assertTrue(sdk.isSupported())
            instrument.runOnMainSync { sdk.initialize();sdk.registerFusedLocationCallback(listener) }
            repeat(20) { if(location.get()==null) {
                instrument.runOnMainSync { sdk.getLastKnownLocation(listener) };Thread.sleep(1000)
            } }
            val value=location.get();assertNotNull("Fresh Pleos GPS observation required",value);value!!
            context.assets.open("roads/korea-v1.zip").use { input -> file.outputStream().use { input.copyTo(it) } }
            RoadPack(file).use { pack ->
                val fix=PlaceFix(value.latitude,value.longitude,Instant.ofEpochMilli(value.timestampMillis))
                val start=SystemClock.elapsedRealtimeNanos()
                val segments=pack.nearby(fix.latitude,fix.longitude)
                val match=RoadMatcher.match(fix,segments)
                val millis=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                val stable=RoadStability()
                assertNull(stable.observe(match,fix.at).grade)
                instrument.sendStatus(0,Bundle().apply { putString("road_pack","grade=${match.grade}; reason=${match.reason}; segments=${segments.size}; coldLookupMs=$millis") })
                assertEquals(expected,match.grade?.name ?: "UNKNOWN")
                assertTrue("Spatial lookup should finish within one sample period",millis<1000)
            }
        } finally {
            file.delete()
            instrument.runOnMainSync { runCatching { sdk.unregisterFusedLocationCallback(listener) };sdk.release() }
        }
    }
}
