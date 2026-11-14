package io.chagok.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.companion.*
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt in with permissionDialog=true after revoking RECORD_AUDIO on the isolated smoke package.
 * Allow the real system prompt after the ALLOW_MICROPHONE status marker (within 25 seconds).
 * Park is injected; the Android permission prompt and Activity lifecycle are real.
 * Automatic questions stay muted so they cannot conceal a lost explicit request.
 */
@RunWith(AndroidJUnit4::class)
class PermissionDialogDeviceTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun await(message: String, condition: () -> Boolean) {
        val end=System.currentTimeMillis()+25000
        while(!condition()) { check(System.currentTimeMillis()<end) { message };Thread.sleep(150) }
    }
    @Test fun realFirstMicrophoneGrantContinuesTheExplicitQuestion() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("permissionDialog")=="true")
        resetSmoke(context)
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val store=SqliteStore(context)
        try {
            val diary=DiaryService(store)
            diary.updateProfile("EV",Powertrain.EV,null);diary.questionQueue("나").muteToday()
            val now=Instant.now()
            store.saveCandidate(DetectionCandidate("permission-charge",diary.profile().id,EnergyKind.SOC,
                Observation(30.0,now.minusSeconds(60)),Observation(80.0,now),CandidateState.PENDING,false))
            ActivityScenario.launch(CompanionHarnessActivity::class.java).use { scenario ->
                scenario.onActivity { it.inject(MotionSnapshot(speedMps=0f,parkGear=true,restricted=false,animationAllowed=true)) }
                scenario.onActivity { all(it.window.decorView).filterIsInstance<TextView>().single { v->v.text.toString()=="메뉴" }.performClick() }
                onView(withText("말로 기록 확인")).perform(click())
                instrument.sendStatus(2,Bundle().apply { putString("chagok_permission","ALLOW_MICROPHONE") })
                await("Question was lost after granting the real microphone permission") {
                    store.entries(diary.profile().id).any { it.candidateId=="permission-charge" && it.status==EntryStatus.DRAFT }
                }
                assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
                assertTrue(diary.questionQueue("나").muted())
                assertEquals(1,diary.drafts().count { it.candidateId=="permission-charge" })
            }
        } finally { store.close() }
    }
}
