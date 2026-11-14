package io.chagok.app

import android.content.Context
import android.content.Intent
import android.Manifest
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import io.chagok.app.companion.*
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import io.chagok.app.ui.MainActivity
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstRunExperienceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun freshInstall() = resetSmoke(context, freshInstall = true)
    private fun all(view: View): List<View> = listOf(view) + if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun text(view: View, value: String) = all(view).filterIsInstance<TextView>().single { it.text.toString() == value }
    private fun <T> db(block: (SqliteStore) -> T): T {
        val store = SqliteStore(context)
        return try { block(store) } finally { store.close() }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end=System.currentTimeMillis()+15000
        while(!condition()) { check(System.currentTimeMillis()<end) { message };Thread.sleep(150) }
    }
    private fun capture(view: View, name: String, widthDp: Int, heightDp: Int) {
        val density = context.resources.displayMetrics.density
        val width = (widthDp*density).toInt(); val height = (heightDp*density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
        view.viewTreeObserver.dispatchOnPreDraw()
        val image = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        File(context.filesDir,"ux-$name-${widthDp}x$heightDp.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
    @Test fun selectionSurvivesRecreationAndRecordingRequiresExplicitStart() {
        ActivityScenario.launch(FirstRunHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(text(activity.window.decorView,"자동 기록 시작").isEnabled)
                text(activity.window.decorView,"전기차").performClick()
                assertTrue(text(activity.window.decorView,"자동 기록 시작").isEnabled)
                db { assertNull(it.setting(RecordingStartup.ENABLED)) }
                val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                for((w,h) in listOf(320 to 800,841 to 1210,1400 to 430)) {
                    capture(root,"welcome",w,h)
                    val start=text(root,"자동 기록 시작")
                    assertTrue(start.bottom<=root.height)
                    assertTrue(start.height>=48*context.resources.displayMetrics.density)
                }
                root.requestLayout()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue((text(activity.window.decorView,"전기차") as android.widget.RadioButton).isChecked)
                db { assertNull(it.setting(RecordingStartup.ENABLED)) }
                text(activity.window.decorView,"자동 기록 시작").performClick()
                db { store ->
                    assertEquals(Powertrain.EV,store.profile()!!.powertrain)
                    assertEquals("true",store.setting(RecordingStartup.ENABLED))
                    assertNull(store.setting(PlaceVisitTracker.ENABLED))
                }
                activity.inject(MonitorState(motion=MotionSnapshot(speedMps=0f,parkGear=true,restricted=false,animationAllowed=true)))
                assertTrue(text(activity.window.decorView,"차량 일지 · 관리  ›").isShown)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(all(activity.window.decorView).none { it is TextView && it.text.toString()=="자동 기록 시작" })
            }
        }
    }
    @Test fun leavingParkBlocksSetupAndDoesNotEnableRecording() {
        ActivityScenario.launch(FirstRunHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                text(activity.window.decorView,"전기차").performClick()
                activity.safelyParked=false
            }
            Thread.sleep(500)
            scenario.onActivity { activity ->
                val start=text(activity.window.decorView,"자동 기록 시작")
                assertFalse(start.isShown);assertFalse(start.isEnabled)
                start.performClick()
                db { assertNull(it.setting(RecordingStartup.ENABLED)) }
            }
        }
    }
    @Test fun parkedHomeShowsAutomaticResultsAndReviewActionInsteadOfCollectionProgress() {
        db { store ->
            FirstRunSetup(store).complete(Powertrain.EV)
            val diary=DiaryService(store)
            diary.questionQueue("나").muteToday()
            val now=Instant.now()
            store.saveTrip(DriveTrip("today",diary.profile().id,"나",now,now,startKm=100.0,endKm=138.2))
            store.saveCandidate(DetectionCandidate("charge",diary.profile().id,EnergyKind.SOC,
                Observation(30.0,now.minusSeconds(60)),Observation(80.0,now),CandidateState.PENDING,false))
        }
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.inject(MotionSnapshot(speedMps=0f,parkGear=true,restricted=false,animationAllowed=true))
            }
            Thread.sleep(1200)
            scenario.onActivity { activity ->
                assertTrue(text(activity.window.decorView,"확인할 기록 1건  ›").isShown)
                assertTrue(all(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("오늘 1회 운행 · 38.2km") })
                assertFalse(text(activity.window.decorView,"주행으로").isShown)
                val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                for((w,h) in listOf(320 to 800,841 to 1210,1400 to 430))capture(root,"home",w,h)
                root.requestLayout()
                activity.inject(MotionSnapshot(speedMps=10f,parkGear=false,restricted=true))
                assertFalse(text(activity.window.decorView,"확인할 기록 1건  ›").isShown)
                assertEquals("오늘의 운행을 기록하고 있어요",text(activity.window.decorView,"오늘의 운행을 기록하고 있어요").text.toString())
            }
        }
    }

    @Test fun productionLauncherStartsAfterSetupAndCanReenableRecordingWithoutMicrophonePrompt() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName,"android.car.permission.CAR_SPEED")
        if(Build.VERSION.SDK_INT>=33)automation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val microphoneBefore=context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(CompanionActivity::class.java).use { scenario ->
            await("Setup did not become available in Park") {
                var ready=false
                scenario.onActivity { ready=text(it.window.decorView,"전기차").isShown }
                ready
            }
            scenario.onActivity { activity ->
                db { assertNull(it.setting(RecordingStartup.ENABLED)) }
                text(activity.window.decorView,"전기차").performClick()
                text(activity.window.decorView,"자동 기록 시작").performClick()
            }
            await("Monitor did not connect after setup") {
                var ready=false
                scenario.onActivity { ready=it.monitorState.motion.speedMps!=null && text(it.window.decorView,"메뉴").isEnabled }
                ready
            }
            assertEquals(microphoneBefore,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            scenario.onActivity { text(it.window.decorView,"메뉴").performClick() }
            onView(withText("자동 기록 종료")).perform(click())
            await("Recording did not stop") { var off=false;scenario.onActivity { off=!it.monitorState.enabled };off }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(text(activity.window.decorView,"메뉴").isEnabled)
                text(activity.window.decorView,"메뉴").performClick()
            }
            onView(withText("자동 기록 켜기")).perform(click())
            await("Recording did not restart") { var enabled=false;scenario.onActivity { enabled=it.monitorState.enabled };enabled }
        }
    }

    @Test fun reviewShortcutOpensReviewInsteadOfTheSummaryScreen() {
        db { FirstRunSetup(it).complete(Powertrain.EV) }
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("openReview",true)).use {
            onView(withText("확인할 기록")).check(matches(isDisplayed()))
            onView(withText("금액을 몰라도 기록할 수 있어요. 미뤄 둔 내용은 여기서 이어가세요.")).check(matches(isDisplayed()))
        }
    }
}
