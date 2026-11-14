package io.chagok.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.companion.*
import io.chagok.app.data.*
import io.chagok.app.domain.*
import io.chagok.app.ui.MainActivity
import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class AutoDiarySmokeTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    @Before fun reset()=resetSmoke(context)
    private fun <T> db(block:(SqliteStore,DiaryService)->T):T = SqliteStore(context).let { try { block(it,DiaryService(it)) } finally { it.close() } }
    private fun await(label:String,seconds:Long=20,test:()->Boolean) {
        val end=System.currentTimeMillis()+seconds*1000
        while(System.currentTimeMillis()<end) { if(test())return;Thread.sleep(150) };fail(label)
    }
    private fun signal(s:String)=instrument.sendStatus(2,Bundle().apply { putString("chagok_signal",s) })
    private fun tap(s:String) { val v=onView(withText(s));if(runCatching { v.check(matches(isDisplayed())) }.isFailure)v.perform(scrollTo());v.perform(click()) }
    @Suppress("DEPRECATION") private fun running()=context.getSystemService(ActivityManager::class.java).getRunningServices(100).any { it.service.className==VehicleMonitorService::class.java.name }
    private fun capture(s:ActivityScenario<MainActivity>,name:String)=s.onActivity { a ->
        val original=Configuration(a.resources.configuration)
        for(width in listOf(841,320,1682)) {
            a.onConfigurationChanged(Configuration(original).apply { screenWidthDp=width;screenHeightDp=1210 })
            val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1210,View.MeasureSpec.EXACTLY));root.layout(0,0,width,1210)
            val bitmap=Bitmap.createBitmap(width,1210,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
            File(context.filesDir,"auto-$name-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            fun check(view:View) {
                if(view is android.widget.TextView&&view.isShown&&view.width>0)view.layout?.let { l ->
                    for(line in 0 until l.lineCount)assertTrue("$name/$width: ${view.text}",l.getLineMax(line)<=view.width-view.compoundPaddingLeft-view.compoundPaddingRight+2)
                }
                if(view is ViewGroup)for(i in 0 until view.childCount)check(view.getChildAt(i))
            };check(root)
        };a.onConfigurationChanged(original)
    }
    @Test fun driveHistorySettingsAndMissingCostCanBeUsedInEveryPane() {
        db { s,d ->
            d.updateProfile("자동 일지 테스트",Powertrain.EV,null)
            val now=Instant.now();s.saveTrip(DriveTrip("ui-trip",d.profile().id,"나",now.minusSeconds(3600),now.minusSeconds(1800),now.minusSeconds(1800),47200.0,47238.2,1500,true,friends=setOf("calico")))
            s.saveGap(RecordingGap("gap",d.profile().id,now.minusSeconds(2800),now.minusSeconds(2600),"차량 연결 공백"))
            s.saveCandidate(DetectionCandidate("ui-charge",d.profile().id,EnergyKind.SOC,Observation(30.0,now.minusSeconds(120)),Observation(80.0,now),CandidateState.PENDING,false))
        }
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            await("Parked") { runCatching { onView(withText("정차 상태가 확인되면 이용할 수 있어요")).check(matches(not(isDisplayed())));true }.getOrDefault(false) }
            capture(s,"summary");tap("오늘의 드라이브");capture(s,"drives")
            onView(withContentDescription("더보기")).perform(click());tap("자동 기록 설정");capture(s,"settings")
            tap("오늘은 묻지 않기");onView(withText("오늘 질문 다시 받기")).check(matches(isDisplayed()))
            onView(withContentDescription("더보기")).perform(click());tap("확인할 기록 1건");capture(s,"review")
            tap("금액 없이 기록");tap("기록하기")
            db { _,d -> assertEquals(1,d.confirmedEntries().size);assertNull(d.confirmedEntries().single().cost);assertEquals(0L,d.ledger(YearMonth.now()).total) }
        }
    }
    @Test fun explicitOffSurvivesActivityAndBootReceiverThenCanBeEnabled() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            await("monitor running") { running() }
            onView(withContentDescription("더보기")).perform(click());tap("자동 기록 설정");tap("자동 기록 끄기")
            await("monitor stopped") { !running() };s.recreate();onView(withText("자동 기록 켜기")).check(matches(isDisplayed()))
            instrument.runOnMainSync { RecordingBootReceiver().onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED)) }
            assertFalse(running());db { st,_->assertEquals("false",st.setting(RecordingStartup.ENABLED)) }
            tap("자동 기록 켜기");await("monitor restarted") { running() }
        }
    }
    @Test fun sdkDriveStartsAutomaticallyAndCompletesAfterParkAcrossServiceRestart() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("virtualConsole")=="true")
        db { _,d->d.updateProfile("EV",Powertrain.EV,null) }
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { s ->
            val observed=java.util.concurrent.atomic.AtomicReference(MonitorState())
            lateinit var connection:MonitorConnection
            s.onActivity { connection=MonitorConnection(it) { state->observed.set(state) };connection.open() }
            signal("BASELINE")
            val expectedKm=InstrumentationRegistry.getArguments().getString("expectedKm")!!.toDouble()
            await("initial odometer received",45) { (observed.get().odometer as? Reading.Value)?.observation?.value==expectedKm }
            Thread.sleep(1500);signal("DRIVE")
            await("auto trip started") { db { st,d->st.trips(d.profile().id).size==1 } }
            Thread.sleep(5500);signal("TRIP_DISTANCE")
            Thread.sleep(6000);signal("PARK")
            await("park checkpoint") { db { st,d->st.trips(d.profile().id).single().parkedSince!=null } }
            val id=db { st,d->st.trips(d.profile().id).single().id }
            s.onActivity { connection.close() }
            context.stopService(Intent(context,VehicleMonitorService::class.java));await("service stopped") { !running() }
            s.onActivity { assertTrue(RecordingStartup.start(it)) }
            await("one trip finished",140) { db { st,d->st.trips(d.profile().id).singleOrNull()?.endedAt!=null } }
            db { st,d ->val trip=st.trips(d.profile().id).single();assertEquals(id,trip.id);assertTrue(trip.movingSeconds>0);assertNotNull("distance should use the observed initial odometer: $trip",trip.distanceKm);assertTrue(trip.distanceKm!!>=0.9) }
        }
    }
    @Test fun versionTwoDataAndNewCheckpointsSurviveSqliteUpgrade() {
        val name="auto-migration.db";check(context.packageName.endsWith(".smoke"));context.deleteDatabase(name)
        val memory=MemoryStore();val d=DiaryService(memory);val original=d.manualDraft(Item.REFUEL,d.today(),10000,Reading.Unavailable("test"),"나")
        context.openOrCreateDatabase(name,0,null).use { sql ->
            sql.execSQL("CREATE TABLE doc (kind TEXT NOT NULL,id TEXT NOT NULL,owner TEXT,json TEXT NOT NULL,PRIMARY KEY(kind,id))")
            sql.execSQL("CREATE INDEX doc_owner ON doc(kind,owner)");sql.version=2
            sql.execSQL("INSERT INTO doc VALUES(?,?,?,?)",arrayOf("entry",original.id,original.vehicleId,Codec.entry(original).toString()))
        }
        val now=Instant.now();val checkpoint=EnergyCheckpoint(Observation(34.0,now),Observation(30.0,now.minusSeconds(60)),now,now,false)
        SqliteStore(context,name).let { st ->try { assertEquals(original,st.entry(original.id));st.saveEnergyCheckpoint(original.vehicleId,EnergyKind.SOC,checkpoint) } finally { st.close() } }
        SqliteStore(context,name).let { st ->try { assertEquals(checkpoint,st.energyCheckpoint(original.vehicleId,EnergyKind.SOC));assertEquals(original,st.entry(original.id)) } finally { st.close() } }
        context.deleteDatabase(name)
    }
}
