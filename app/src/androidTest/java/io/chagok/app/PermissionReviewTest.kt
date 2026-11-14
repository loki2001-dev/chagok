package io.chagok.app

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.chagok.app.companion.*
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionReviewTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val clear=MotionSnapshot(speedMps=0f,parkGear=true,restricted=false,animationAllowed=true)
    @Before fun reset() {
        resetSmoke(context)
        db { store ->
            val diary=DiaryService(store)
            diary.updateProfile("EV",Powertrain.EV,null)
            // A manual request must survive even when automatic questions cannot rescue it.
            diary.questionQueue("나").muteToday()
            val now=Instant.now()
            store.saveCandidate(DetectionCandidate("charge",diary.profile().id,EnergyKind.SOC,
                Observation(30.0,now.minusSeconds(60)),Observation(80.0,now),CandidateState.PENDING,false))
        }
    }
    private fun <T> db(block: (SqliteStore)->T):T {
        val store=SqliteStore(context)
        return try { block(store) } finally { store.close() }
    }
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun text(a:PermissionReviewHarnessActivity,value:String)=all(a.window.decorView).filterIsInstance<TextView>().single { it.text.toString()==value }

    @Test fun independentRestrictionClosesParkedSurfacesWhenRecordingIsOff() {
        ActivityScenario.launch(PermissionReviewHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.inject(MonitorState(enabled=false));a.drivingParked(true)
                a.openCollection()
                assertTrue(a.showingCollection)
                a.scene.preview=true
            }
            // AAOS can reparent a newly launched surface and dismiss its first dialog.
            val deadline=System.currentTimeMillis()+10000
            while (true) {
                scenario.onActivity { text(it,"메뉴").performClick() }
                if (runCatching { onView(withText("주행 연출 미리보기")).check(matches(isDisplayed())) }.isSuccess) break
                check(System.currentTimeMillis()<deadline) { "Preview menu did not open" }
            }
            onView(withText("주행 연출 미리보기")).check(matches(isDisplayed()))
            scenario.onActivity { a ->
                a.scene.preview=false
                assertTrue(a.beginQuestion("charge"))
                a.scene.preview=true
                assertTrue(a.conversation.asking)
                a.drivingParked(false)
                assertFalse(a.showingCollection);assertFalse(a.scene.preview)
                assertFalse(a.conversation.asking)
                assertFalse(text(a,"메뉴").isEnabled)
                assertFalse(text(a,"주행으로").isShown)
            }
            onView(withText("주행 연출 미리보기")).check(doesNotExist())
        }
    }

    @Test fun deniedVehiclePermissionShowsRecoveryAndDoesNotRequestNotificationsAgain() {
        ActivityScenario.launch(PermissionReviewHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.speedPermission=false;a.inject(MonitorState(motion=clear.copy(speedMps=null)))
                text(a,"차량 권한 허용하기").performClick()
                assertEquals(listOf("android.car.permission.CAR_SPEED"),a.requestedPermissions)
                a.onRequestPermissionsResult(1042,arrayOf("android.car.permission.CAR_SPEED"),intArrayOf(-1))
                assertTrue(text(a,"운행 기록 준비 미완료 · 차량 권한 필요").isShown)
                text(a,"차량 권한 허용하기").performClick()
                assertTrue("Repeated denial should have a Settings recovery path",a.settingsOpened)
            }
            Thread.sleep(1200)
            scenario.onActivity { a ->
                assertTrue(all(a.window.decorView).filterIsInstance<TextView>().none { it.text.contains("자동으로 남아요") })
                val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val density=a.resources.displayMetrics.density
                root.measure(View.MeasureSpec.makeMeasureSpec((320*density).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((800*density).toInt(),View.MeasureSpec.EXACTLY))
                root.layout(0,0,root.measuredWidth,root.measuredHeight);root.viewTreeObserver.dispatchOnPreDraw()
                val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(context.filesDir,"ux-permission-recovery-320x800.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle();root.requestLayout()
                a.speedPermission=true;a.inject(MonitorState(motion=clear))
                assertTrue(text(a,"확인할 기록 1건  ›").isShown)
                // Notification permission alone is optional and must not block recording readiness.
                a.onRequestPermissionsResult(1042,arrayOf(Manifest.permission.POST_NOTIFICATIONS),intArrayOf(-1))
                assertTrue(text(a,"확인할 기록 1건  ›").isShown)
            }
        }
    }

    @Test fun microphoneResultWhilePausedResumesOnceEvenWhenQuestionsAreMuted() {
        ActivityScenario.launch(PermissionReviewHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { a -> a.inject(MonitorState(motion=clear));a.askWithPermission("charge")
                assertEquals(listOf(Manifest.permission.RECORD_AUDIO),a.requestedPermissions) }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { a -> a.microphoneResult(true);assertEquals(EnergyConversation.Stage.IDLE,a.conversation.stage) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { a ->
                assertTrue(a.conversation.asking)
                db { assertEquals(1,DiaryService(it).drafts().size) }
                a.conversation.clear();a.microphoneResult(true);a.inject(MonitorState(motion=clear))
                assertEquals(EnergyConversation.Stage.IDLE,a.conversation.stage)
            }
        }
    }

    @Test fun pendingPermissionSurvivesRecreationAndWaitsUntilParked() {
        ActivityScenario.launch(PermissionReviewHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { a ->a.inject(MonitorState(motion=clear));a.askWithPermission("charge") }
            scenario.recreate()
            scenario.onActivity { a ->
                a.inject(MonitorState(motion=clear.copy(speedMps=10f,restricted=true,parkGear=false)))
                a.microphoneResult(true)
                assertFalse(a.conversation.asking)
                a.inject(MonitorState(motion=clear))
                assertTrue(a.conversation.asking)
            }
        }
    }

    @Test fun deniedPermissionDoesNotLeaveAQuestionToStartAfterAnUnrelatedGrant() {
        ActivityScenario.launch(PermissionReviewHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.inject(MonitorState(motion=clear));a.askWithPermission("charge");a.microphoneResult(false)
                a.microphoneResult(true);a.inject(MonitorState(motion=clear))
                assertFalse(a.conversation.asking)
                db { assertTrue(DiaryService(it).drafts().isEmpty()) }
            }
        }
    }

    @Test fun permissionRequestIsNotTransferredToAnotherUser() {
        ActivityScenario.launch(PermissionReviewHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { a ->a.inject(MonitorState(motion=clear));a.askWithPermission("charge") }
            scenario.moveToState(Lifecycle.State.CREATED)
            db { it.saveSetting("user","가족") }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { a ->a.microphoneResult(true);a.inject(MonitorState(motion=clear));assertFalse(a.conversation.asking) }
            db { assertTrue(DiaryService(it).drafts().isEmpty()) }
        }
    }
}
