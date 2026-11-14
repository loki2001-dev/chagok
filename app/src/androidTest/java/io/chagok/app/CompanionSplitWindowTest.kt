package io.chagok.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.companion.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real Pleos window arrangement + live SDK signal test; no fabricated navigation UI. */
@RunWith(AndroidJUnit4::class)
class CompanionSplitWindowTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun signal(name: String) = instrument.sendStatus(2, Bundle().apply { putString("chagok_signal", name) })
    private fun await(name: String, timeout: Long = 30000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        do { if (predicate()) return; Thread.sleep(150) } while (SystemClock.elapsedRealtime() < end)
        fail("Timed out: $name")
    }
    @Test fun liveSdkAnimationContinuesBesideFocusedNavigation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("splitProbe") == "true")
        resetSmoke(context)
        ActivityScenario.launch(CompanionDrivingProbeActivity::class.java).use { scenario ->
            fun sample(): JSONObject {
                var result = JSONObject()
                scenario.onActivity { a ->
                    val bounds = Rect(); a.scene.getGlobalVisibleRect(bounds)
                    val origin = IntArray(2); a.window.decorView.getLocationOnScreen(origin)
                    result = JSONObject().put("speedMps", a.sdkState.motion.speedMps)
                        .put("restricted", a.sdkState.motion.restricted)
                        .put("productionAnimationAllowed", a.sdkState.motion.animationAllowed)
                        .put("probeAnimationAllowed", a.scene.motion.animationAllowed)
                        .put("preview", a.scene.preview).put("shown", a.scene.isShown)
                        .put("windowVisible", a.scene.windowVisibility == View.VISIBLE)
                        .put("focused", a.hasWindowFocus()).put("left", origin[0]).put("top", origin[1])
                        .put("width", a.window.decorView.width).put("height", a.window.decorView.height)
                        .put("sceneWidth", a.scene.width).put("sceneHeight", a.scene.height)
                        .put("carWidthPx", a.scene.renderState.width * a.scene.sceneLayout.scale)
                        .put("frames", a.scene.renderState.frames).put("travel", a.scene.travel)
                        .put("rain", a.scene.renderState.rain).put("boost", a.scene.renderState.boost)
                }; return result
            }
            await("SDK connected") { sample().has("speedMps") && sample().optDouble("speedMps", -1.0) == 0.0 }
            signal("ARRANGE_SPLIT")
            await("right portrait pane") { val s = sample(); s.getInt("left") >= 1700 && s.getInt("width") < 900 && s.getBoolean("shown") }
            signal("DRIVE")
            await("real SDK driving state") { val s = sample(); s.optDouble("speedMps") > 6 && s.getBoolean("restricted") }
            signal("FOCUS_NAVIGATION")
            await("navigation focused, companion visible") { val s = sample(); !s.getBoolean("focused") && s.getBoolean("shown") && s.getBoolean("windowVisible") }
            val before = sample(); val at = SystemClock.elapsedRealtime()
            signal("RECORD_SPLIT")
            Thread.sleep(5000)
            val after = sample()
            assertFalse(after.getBoolean("preview"))
            assertFalse(after.getBoolean("productionAnimationAllowed"))
            assertTrue(after.getBoolean("probeAnimationAllowed"))
            assertEquals("Scene fills the real split window",after.getInt("width"),after.getInt("sceneWidth"))
            assertTrue("Most of the pane remains the road",after.getInt("sceneHeight")>after.getInt("height")*.65)
            assertTrue("Frames advance while navigation has focus", after.getLong("frames") - before.getLong("frames") > 20)
            assertNotEquals("Road travels with live SDK speed", before.getDouble("travel"), after.getDouble("travel"), .1)
            val evidence = JSONObject().put("before", before).put("after", after)
                .put("elapsedMs", SystemClock.elapsedRealtime() - at)
            instrument.uiAutomation.takeScreenshot().let { bmp ->
                File(context.filesDir, "companion-split-driving.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle()
            }
            scenario.onActivity { a ->
                val view=a.findViewById<View>(android.R.id.content)
                val bmp=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bmp))
                File(context.filesDir,"companion-split-app.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
                bmp.recycle()
            }
            // Exercise real acceleration through speed input, not renderer preview fields.
            signal("ACCELERATE")
            var maxBoost = 0.0
            val boostEnd = SystemClock.elapsedRealtime() + 6000
            while (SystemClock.elapsedRealtime() < boostEnd) {
                val s = sample(); maxBoost = maxOf(maxBoost, s.getDouble("boost")); Thread.sleep(80)
            }
            assertTrue("Actual speed rise triggers booster", maxBoost > .15)
            evidence.put("maxBoost", maxBoost)
            File(context.filesDir, "companion-split-driving.json").writeText(evidence.toString(2))
            signal("PARK")
            await("Park restored") { val s = sample(); s.optDouble("speedMps", -1.0) == 0.0 && !s.getBoolean("restricted") }
        }
    }
}
