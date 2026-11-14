package io.chagok.app

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import io.chagok.app.drive.DrivingState
import io.chagok.app.ui.MainActivity
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** tools/smoke-device.py injects simulated values through Pleos Virtual Console. */
@RunWith(AndroidJUnit4::class)
class VehicleSmokeTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun signal(name: String) = instrument.sendStatus(2, Bundle().apply { putString("chagok_signal", name) })
    private fun await(name: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 45000
        do { if (check()) return; Thread.sleep(250) } while (System.currentTimeMillis() < end)
        fail("Timed out: $name")
    }
    private fun <T> db(block: (SqliteStore, DiaryService) -> T): T {
        val store = SqliteStore(context)
        return try { block(store, DiaryService(store)) } finally { store.close() }
    }

    @Test fun realVehicleSignalToCandidateAndDrivingRestriction() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualConsole") == "true")
        resetSmoke(context)
        db { _, app -> app.updateProfile("EV smoke", Powertrain.EV, null) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            signal("BASELINE")
            await("30% baseline from SDK") { db { store, app -> store.energyBaseline(app.profile().id, EnergyKind.SOC)?.value == 30.0 } }
            // Fresh odometer retrieval follows the same SDK path as normal input.
            val expectedKm = InstrumentationRegistry.getArguments().getString("expectedKm")!!.toInt()
            await("odometer SDK reading") { runCatching { onView(withText("%,dkm".format(expectedKm))).check(matches(isDisplayed())); true }.getOrDefault(false) }
            signal("RISE")
            await("stable rise candidate") { db { _, app -> app.pendingCandidates().any { it.before.value == 30.0 && it.after.value == 80.0 } } }
            db { _, app -> assertTrue(app.confirmedEntries().isEmpty()); assertEquals(0L, app.ledger(java.time.YearMonth.now()).total) }
            signal("DRIVE")
            await("driving restriction") {
                var restricted = false
                instrument.runOnMainSync { val drive = DrivingState(context) {}; drive.refresh(); restricted = drive.restricted; drive.release() }
                restricted
            }
            signal("PARK")
            // The platform may hide a non-DO app while driving; explicitly bring it back after Park.
            await("parked restriction clear") {
                var parked = false
                instrument.runOnMainSync { val drive = DrivingState(context) {}; drive.refresh(); parked = !drive.restricted; drive.release() }
                parked
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
    }

    @Test fun onDeviceSpeechStartCancelDoesNotSave() {
        resetSmoke(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            await("parked screen before speech") { runCatching {
                onView(withText("정차 상태가 확인되면 이용할 수 있어요")).check(matches(not(isDisplayed()))); true
            }.getOrDefault(false) }
            // AAOS reparents the app surface after clearing the driving overlay. Wait for navigation
            // to land instead of assuming its first injected tap arrived during that transition.
            await("input screen after parked surface transition") { runCatching {
                onView(withContentDescription("더보기")).perform(click())
                onView(withText("기록 추가")).perform(click())
                onView(withText("기록 추가")).check(matches(isDisplayed())); true
            }.getOrDefault(false) }
            await("speech backend ready") { runCatching { onView(withText("말하기 시작")).check(matches(isEnabled())); true }.getOrDefault(false) }
            onView(withText("말하기 시작")).perform(click())
            // No microphone audio is supplied: cancel if still active, otherwise verify the error path remains idle.
            Thread.sleep(800)
            runCatching { onView(withText("취소")).perform(click()) }
            await("speech idle") { runCatching { onView(withText("말하기 시작")).check(matches(isDisplayed())); true }.getOrDefault(false) }
            db { _, app -> assertTrue(app.drafts().isEmpty()); assertTrue(app.confirmedEntries().isEmpty()) }
        }
    }
}
