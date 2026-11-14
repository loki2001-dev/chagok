package io.chagok.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.companion.*
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class ServiceVisitAnimationDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val parked = MotionSnapshot(speedMps = 0f, parkGear = true, restricted = false, animationAllowed = true)
    @Before fun isolate() = resetSmoke(context)

    private fun <T> db(block: (SqliteStore, DiaryService) -> T): T {
        val store = SqliteStore(context)
        return try { block(store, DiaryService(store).apply { selectUser("나") }) } finally { store.close() }
    }
    private fun capture(scene: CompanionSceneView, name: String, w: Int, h: Int): Bitmap {
        scene.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        scene.layout(0, 0, w, h)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
            scene.draw(Canvas(bitmap))
            File(context.filesDir, "service-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun visitPreviewFromCollectionReturnsToTheVisibleScene() {
        fun buttons(view: View): List<Button> = when (view) {
            is Button -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else -> emptyList()
        }
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { scenario ->
            for (kind in PlaceKind.entries) {
                scenario.onActivity { a ->
                    a.inject(parked); a.scene.preview = true
                    buttons(a.window.decorView).single { it.text == "친구 0/10" }.performClick()
                    assertTrue(a.showingCollection)
                    assertEquals(View.GONE, a.scene.visibility)
                }
                val option = if (kind == PlaceKind.FUEL) "주유소 방문" else "정비소 방문"
                val deadline = System.currentTimeMillis() + 10000
                // AAOS may reparent the window and dismiss its first dialog after launch.
                while (!runCatching { onView(withText(option)).check(matches(isDisplayed())); true }.getOrDefault(false)) {
                    check(System.currentTimeMillis() < deadline) { "Visit preview menu did not appear" }
                    scenario.onActivity { a -> buttons(a.window.decorView).single { it.text == "메뉴" }.performClick() }
                    Thread.sleep(200)
                }
                onView(withText(option)).perform(click())
                scenario.onActivity { a ->
                    assertFalse("A service preview must leave the collection", a.showingCollection)
                    assertEquals(View.VISIBLE, a.scene.visibility)
                    assertEquals(kind, a.scene.serviceVisit.frame?.kind)
                    a.scene.advance(3f)
                    assertEquals(ServiceVisitAnimation.Phase.WORK, a.scene.serviceVisit.frame?.phase)
                }
            }
        }
    }

    @Test fun bothServicesAnimateInBothLayoutsAndPreviewDoesNotWrite() {
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.inject(parked); a.scene.preview = true; a.scene.director.active = null
                for (skin in CarSkin.entries) for (kind in PlaceKind.entries) for ((w, h) in listOf(480 to 750, 1200 to 600)) {
                    a.scene.skin = skin
                    assertTrue(a.scene.previewService(kind))
                    a.scene.advance(1f)
                    capture(a.scene, "${kind.name}-${skin.name}-${w}-approach", w, h).recycle()
                    a.scene.advance(2f)
                    val work = capture(a.scene, "${kind.name}-${skin.name}-${w}-work", w, h)
                    assertEquals(ServiceVisitAnimation.Phase.WORK, a.scene.serviceVisit.frame!!.phase)
                    assertTrue(a.scene.contentDescription.contains(if (kind == PlaceKind.FUEL) "주유 중" else "점검 중"))
                    a.scene.advance(.25f)
                    capture(a.scene, "${kind.name}-${skin.name}-${w}-work-next", w, h).recycle()
                    a.scene.advance(8f)
                    assertEquals(ServiceVisitAnimation.Phase.LEAVE, a.scene.serviceVisit.frame!!.phase)
                    capture(a.scene, "${kind.name}-${skin.name}-${w}-leave", w, h).recycle()
                    a.scene.advance(2f)
                    assertNull(a.scene.serviceVisit.frame)
                    val done = capture(a.scene, "${kind.name}-${skin.name}-${w}-done", w, h)
                    assertFalse("Service artwork must be visible", work.sameAs(done))
                    work.recycle(); done.recycle()
                }
                if (InstrumentationRegistry.getArguments().getString("serviceDemo") == "true") {
                    a.scene.skin = CarSkin.HYUNDAI
                    for (kind in PlaceKind.entries) {
                        assertTrue(a.scene.previewService(kind))
                        repeat(140) { index ->
                            capture(a.scene, "demo-${kind.name}-${index.toString().padStart(3, '0')}", 1200, 600).recycle()
                            a.scene.advance(.1f)
                        }
                    }
                }
                assertTrue(a.scene.previewService(PlaceKind.REPAIR))
                a.inject(parked.copy(speedMps = 2f, parkGear = false))
                assertNull(a.scene.serviceVisit.frame)
            }
        }
        db { s, d -> assertTrue(s.visits(d.profile().id).isEmpty()); assertTrue(s.entries(d.profile().id).isEmpty()) }
    }

    @Test fun detectedVisitStartsOnceAndSurvivesRecreationWithoutWritingServiceHistory() {
        val visit = db { s, d ->
            d.updateProfile("테스트 차량", Powertrain.ICE, null)
            s.saveSetting(PlaceVisitTracker.ENABLED, "true")
            val place = ServicePlace("synthetic", "합성 주유소", PlaceKind.FUEL, 37.5, 127.0, "", "device-test")
            s.replacePlaces(listOf(place))
            val tracker = PlaceVisitTracker(s)
            val now = Instant.now()
            (0L..60L step 10).forEach { seconds ->
                val at = now.minusSeconds(60).plusSeconds(seconds)
                tracker.sample(d.profile().id, "나", PlaceFix(37.5, 127.0, at), at, true, null)
            }
            // Muting questions must not disable the visual welcome.
            d.questionQueue("나").muteToday()
            s.visits(d.profile().id).single()
        }
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { it.inject(parked) }
            Thread.sleep(1400)
            scenario.onActivity { a ->
                assertEquals(visit.id, a.scene.serviceVisit.frame?.visitId)
                a.scene.advance(3f)
                capture(a.scene, "detected-fuel", 1200, 600).recycle()
            }
            scenario.recreate()
            scenario.onActivity { it.inject(parked) }
            Thread.sleep(1400)
            scenario.onActivity { assertNull(it.scene.serviceVisit.frame) }
            // A subsequent visit has its own identity and gets its own welcome.
            db { s, _ ->
                s.saveVisit(visit.copy(exited = true))
                s.saveVisit(visit.copy(id = "next-visit", place = visit.place.copy(kind = PlaceKind.REPAIR), lastAt = Instant.now()))
            }
            Thread.sleep(1400)
            scenario.onActivity { a ->
                assertEquals(PlaceKind.REPAIR, a.scene.serviceVisit.frame?.kind)
            }
            db { s, d -> s.saveVisit(s.visits(d.profile().id).single { it.id == "next-visit" }.copy(state = VisitState.EXCLUDED)) }
            Thread.sleep(1400)
            scenario.onActivity { assertNull(it.scene.serviceVisit.frame) }
            db { s, _ -> s.saveVisit(visit.copy(id = "third-visit", lastAt = Instant.now())) }
            Thread.sleep(1400)
            scenario.onActivity { a ->
                assertEquals("third-visit", a.scene.serviceVisit.frame?.visitId)
                a.inject(parked.copy(animationAllowed = false))
                assertNull(a.scene.serviceVisit.frame)
            }
        }
        db { s, d ->
            assertTrue(s.entries(d.profile().id).isEmpty())
            assertTrue(s.visits(d.profile().id).none { it.state == VisitState.CONFIRMED })
        }
    }
}
