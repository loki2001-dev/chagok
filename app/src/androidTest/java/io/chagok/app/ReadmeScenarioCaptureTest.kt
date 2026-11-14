package io.chagok.app

import android.app.Activity
import android.content.Context
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
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import io.chagok.app.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.time.*

/** Explicit documentation capture: real Views/domain/storage, labelled fixture data. */
@RunWith(AndroidJUnit4::class)
class ReadmeScenarioCaptureTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<String>()
    private val km get() = InstrumentationRegistry.getArguments().getString("expectedKm")!!.toDouble()
    private fun <T> db(block: (SqliteStore, DiaryService) -> T): T = SqliteStore(context).let { s ->
        try { block(s, DiaryService(s)) } finally { s.close() }
    }
    @Before fun reset() {
        // Do not reset data or generate assets during a normal full smoke run.
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.contains("ReadmeScenarioCaptureTest") == true)
        resetSmoke(context)
    }
    private class ScenarioClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.systemDefault()
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(at, zone)
        override fun instant(): Instant = at
    }
    private fun capture(a: Activity, name: String) {
        val original = Configuration(a.resources.configuration)
        a.onConfigurationChanged(Configuration(original).apply { screenWidthDp = 841; screenHeightDp = 1210 })
        val view = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val density = a.resources.displayMetrics.density
        val w = (841 * density).toInt(); val h = (1210 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h); view.viewTreeObserver.dispatchOnPreDraw()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(context.filesDir, "readme-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); files.add("$name.png")
        a.onConfigurationChanged(original)
    }
    private fun tap(label: String) {
        val view = onView(withText(label))
        if (runCatching { view.check(matches(isDisplayed())) }.isFailure) view.perform(scrollTo())
        view.perform(click())
    }

    @Test fun chargeDayFromCandidateToConfirmedDiary() {
        // Keep the live SDK's current odometer/SOC consistent with the labelled fixture.
        val instrument = InstrumentationRegistry.getInstrumentation()
        for (signal in listOf("BASELINE", "RISE")) {
            instrument.sendStatus(2, Bundle().apply { putString("chagok_signal", signal) })
        }
        val now = Instant.now()
        val candidate = db { store, _ ->
            val clock = ScenarioClock(now.minusSeconds(2820))
            val diary = DiaryService(store, clock)
            diary.updateProfile("우리 전기차", Powertrain.EV, null)
            val old = diary.manualDraft(Item.TIRE_ROTATION, diary.today().minusMonths(3), 30000L, Reading.Unavailable("fixture"), "나")
            diary.updateEntry(old.id, old.performedOn, km - 2000, old.cost, null, "시연용 정비 기록", emptySet())
            assertTrue(diary.confirm(old.id) is DiaryService.ConfirmResult.Saved)
            diary.setPlan(Item.TIRE_ROTATION, 10000, 12, "시연용 주기 · 실제 차량 매뉴얼로 설정")
            store.saveTrip(DriveTrip("readme-trip", diary.profile().id, "나", clock.at.minusSeconds(1800), clock.at,
                endedAt = clock.at, startKm = km - 38.2, endKm = km, movingSeconds = 1500, odometerAt = clock.at))
            fun sample(value: Double) { clock.at = clock.at.plusSeconds(10); diary.observeEnergy(EnergyKind.SOC, value, true) }
            diary.observeEnergy(EnergyKind.SOC, 30.0, true); sample(30.0)
            for (value in 31..80) { sample(value.toDouble()); sample(value.toDouble()) }
            repeat(181) { sample(80.0) }
            diary.pendingCandidates().single().also {
                assertFalse(it.collecting)
                assertEquals(30.0, it.before.value, 0.0)
                assertEquals(80.0, it.after.value, 0.0)
            }
        }
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { s ->
            s.onActivity { a ->
                val parked = MonitorState(motion = MotionSnapshot(speedMps = 0f, restricted = false, animationAllowed = true, parkGear = true),
                    kind = EnergyKind.SOC, energy = 80.0, energyStatus = "배터리 80%", odometer = Reading.Value(Observation(km, Instant.now())), recordingStatus = "자동 기록 중")
                a.scene.preview = true; a.scene.previewSpeed = 45f; a.scene.skin = CarSkin.HYUNDAI
                a.inject(parked); a.scene.begin(Encounters.all.first(), immediate = true)
                repeat(10) { a.scene.advance(.05f) }
                capture(a, "01-companion")
                a.scene.preview = false; a.scene.director.active = null; a.inject(parked)
                assertTrue(a.beginQuestion(candidate.id)); capture(a, "02-question")
                a.receiveAnswer("1만 2천 원")
                assertEquals(EnergyConversation.Stage.CONFIRM, a.conversation.stage)
                db { _, diary -> assertEquals(0L, diary.ledger(YearMonth.now()).total) }
                capture(a, "03-confirm")
                a.receiveAnswer("맞아")
                db { _, diary ->
                    assertEquals(12000L, diary.ledger(YearMonth.now()).total)
                    assertEquals(1, diary.confirmedEntries().count { it.item == Item.CHARGE })
                }
                capture(a, "04-saved")
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            val until = System.currentTimeMillis() + 45000
            while (runCatching { onView(withText("%,.0fkm".format(km))).check(matches(isDisplayed())) }.isFailure) {
                check(System.currentTimeMillis() < until) { "SDK odometer did not match the scenario" }
                Thread.sleep(200)
            }
            s.onActivity { capture(it, "05-summary") }
            tap("일지"); s.onActivity { capture(it, "06-diary") }
            onView(withContentDescription("${LocalDate.now()} 충전 기록 상세")).perform(scrollTo(), click())
            s.onActivity { capture(it, "07-detail") }
            tap("통계"); s.onActivity { capture(it, "08-statistics") }
            tap("요약"); tap("오늘의 드라이브"); s.onActivity { capture(it, "09-drives") }
            tap("관리"); s.onActivity { capture(it, "10-maintenance") }
        }
        File(context.filesDir, "readme-scenario.json").writeText(JSONObject().apply {
            put("capturedAt", Instant.now().toString()); put("package", context.packageName)
            put("viewportDp", "841x1210"); put("images", org.json.JSONArray(files))
            put("fixture", true); put("tripKm", 38.2); put("observedMovingSeconds", 1500)
            put("socBefore", 30); put("socAfter", 80); put("odometerKm", km)
            put("costBeforeConfirmation", 0); put("costAfterConfirmation", 12000)
            put("method", "Native Android Views with deterministic fixtures; real energy detector, conversation and SQLite. Text delivered at STT-result boundary; not a microphone or live-driving test. First frame is parked preview.")
        }.toString(2))
    }
}
