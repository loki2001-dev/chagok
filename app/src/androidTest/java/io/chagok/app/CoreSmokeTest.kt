package io.chagok.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.data.Codec
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import io.chagok.app.drive.DrivingState
import io.chagok.app.ui.MainActivity
import org.hamcrest.Matchers.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

/** Real SQLite and real parked Pleos UI, isolated from the normal application's database. */
@RunWith(AndroidJUnit4::class)
class CoreSmokeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun isolate() {
        resetSmoke(context)
    }

    private fun <T> db(block: (SqliteStore, DiaryService) -> T): T {
        val store = SqliteStore(context)
        return try { block(store, DiaryService(store)) } finally { store.close() }
    }
    private fun await(description: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 12000
        do { if (predicate()) return; Thread.sleep(150) } while (System.currentTimeMillis() < end)
        fail("Timed out: $description")
    }
    private fun tap(text: String) {
        val view = onView(withText(text))
        try {
            if (runCatching { view.check(matches(isDisplayed())) }.isFailure) view.perform(scrollTo())
            view.perform(click())
        } catch (e: Throwable) {
            java.io.File(context.filesDir, "smoke-failure.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            throw e
        }
    }
    private fun nav(index: Int, text: String) {
        when (index) {
            1 -> tap("요약")
            4 -> tap("일지")
            5 -> tap("관리")
            else -> {
                onView(withContentDescription("더보기")).perform(click())
                if (index == 3) onView(withText(startsWith("확인할 기록 "))).perform(click())
                else tap(if (index == 2) "기록 추가" else "관리 도감")
            }
        }
    }
    private fun openOilRecord() {
        onView(withContentDescription("${LocalDate.now()} 엔진오일 교환 기록 상세")).perform(scrollTo(), click())
    }
    private fun fill(hint: String, value: String) = onView(withHint(hint)).perform(scrollTo(), replaceText(value), closeSoftKeyboard())
    private fun parked() {
        await("parked screen") {
            runCatching { onView(withText("정차 상태가 확인되면 이용할 수 있어요")).check(matches(not(isDisplayed()))); true }.getOrDefault(false)
        }
    }

    @Test fun sqliteUpgradeAndRollbackPreserveExistingRecords() {
        val memory = MemoryStore(); val service = DiaryService(memory)
        val old = service.manualDraft(Item.ENGINE_OIL, service.today(), 80000, Reading.Unavailable("test"), "나")
        val oldJson = Codec.entry(old).apply { remove("updatedAt"); remove("energyAmount"); remove("detachedObservation") }
        context.openOrCreateDatabase("chagok.db", 0, null).use { raw ->
            raw.execSQL("CREATE TABLE doc (kind TEXT NOT NULL, id TEXT NOT NULL, owner TEXT, json TEXT NOT NULL, PRIMARY KEY(kind,id))")
            raw.execSQL("INSERT INTO doc VALUES(?,?,?,?)", arrayOf("profile", "current", null, Codec.profile(service.profile()).toString()))
            raw.execSQL("INSERT INTO doc VALUES(?,?,?,?)", arrayOf("entry", old.id, old.vehicleId, oldJson.toString()))
            raw.version = 1
        }
        db { store, app ->
            assertEquals(old.createdAt, app.entry(old.id)!!.updatedAt)
            assertTrue(app.confirm(old.id) is DiaryService.ConfirmResult.Saved)
            assertTrue(runCatching { store.transaction { store.deleteEntry(old.id); error("rollback") } }.isFailure)
            assertEquals(EntryStatus.CONFIRMED, app.entry(old.id)!!.status)
        }
        db { _, app -> assertEquals(80000L, app.ledger(YearMonth.now()).total); assertEquals(1, app.stamps("나").size) }
    }

    @Test fun persistedCandidateSharingAndAllUserStampReview() {
        var id = ""
        db { store, app ->
            app.updateProfile("EV", Powertrain.EV, null)
            val at = Instant.now()
            store.saveCandidate(DetectionCandidate("candidate", app.profile().id, EnergyKind.SOC,
                Observation(20.0, at.minusSeconds(60)), Observation(80.0, at), CandidateState.PENDING, false))
            id = app.draftFromCandidate("candidate", "A", Reading.Unavailable("test"))!!.id
            app.updateEntry(id, app.today(), 47200.0, 12000, 30.5, "private", emptySet())
            assertTrue(app.confirm(id) is DiaryService.ConfirmResult.Saved)
            store.saveProgress(LearningProgress("B", Topic.FIRST_DIARY, 1, at, id))
            app.selectUser("A"); app.setSharing("A", setOf("B"))
        }
        db { store, app ->
            app.selectUser("B"); assertEquals(30.5, app.entry(id)!!.energyAmount!!, 0.0)
            app.selectUser("C"); assertTrue(app.timeline().isEmpty())
            app.selectUser("A"); app.cancel(id)
            assertEquals(CandidateState.PENDING, store.candidates(app.profile().id).single().state)
            assertTrue(store.allProgress().all { it.needsReview })
            assertEquals(0L, app.ledger(YearMonth.now()).total)
        }
    }

    @Test fun drivingServiceDisconnectFailsClosed() {
        instrumentation.runOnMainSync {
            val drive = DrivingState(context) {}
            assertTrue("Pleos service connected", drive.available)
            drive.refresh(); assertFalse("Run tests with gear Park", drive.restricted)
            drive.release(); drive.refresh(); assertTrue(drive.restricted)
        }
    }

    @Test fun uiRecordRecreateEditReportLearningAndCancel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            parked()
            db { _, app -> app.setPlan(Item.ENGINE_OIL, 10000, 12, "테스트 매뉴얼"); app.setPlan(Item.TIRE_ROTATION, 10000, 12, "테스트 매뉴얼") }
            nav(2, "정비 입력")
            fill("예: 엔진오일 교환 8만 원", "오늘 엔진오일 갈았어. 8만 원")
            tap("해석")
            await("draft") { db { _, app -> app.drafts().size == 1 } }
            fill("메모 (선택)", "스모크 메모")
            scenario.recreate(); parked()
            onView(withHint("메모 (선택)")).check(matches(withText("스모크 메모")))
            tap("확인하고 저장")
            await("confirmed") { db { _, app -> app.confirmedEntries().size == 1 } }
            db { _, app -> assertEquals(80000L, app.ledger(YearMonth.now()).total); assertEquals(1, app.stamps("나").size) }
            nav(6, "관리 도감")
            onView(withText("일지 첫걸음")).check(matches(isDisplayed()))
            val lesson = db { _, app -> app.recommendedMission("나", 47200.0)!! }
            tap(lesson.choices[1])
            onView(withText(startsWith("힌트:"))).check(matches(isDisplayed()))
            tap(lesson.choices[lesson.answerIndex])
            db { _, app -> assertEquals(2, app.stamps("나").size) }
            nav(4, "일지·비용")
            openOilRecord(); tap("수정하기")
            fill("비용 원", "70000"); fill("메모", "수정 메모"); tap("저장")
            db { _, app -> assertEquals(70000L, app.ledger(YearMonth.now()).total); assertEquals(2, app.confirmedEntries().single().version) }
            nav(4, "일지·비용"); tap("정비 리포트 내보내기"); tap("저장")
            onView(withText("리포트 미리보기")).check(matches(isDisplayed()))
            onView(withId(android.R.id.message)).check(matches(allOf(withText(containsString("70,000")), withText(not(containsString("수정 메모"))))))
            tap("닫기")
            openOilRecord(); tap("수정하기"); tap("기록 취소"); tap("취소하기")
            db { _, app -> assertEquals(0L, app.ledger(YearMonth.now()).total); assertTrue(app.stamps("나").all { it.needsReview }) }
        }
    }

    @Test fun uiEnergyCandidateQuantityAndExclusion() {
        db { store, app ->
            app.updateProfile("전기차", Powertrain.EV, null)
            val at = Instant.now()
            store.saveCandidate(DetectionCandidate("c", app.profile().id, EnergyKind.SOC,
                Observation(20.0, at.minusSeconds(60)), Observation(80.0, at), CandidateState.PENDING, false))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            parked(); nav(3, "자동 기록 후보"); tap("충전으로 기록")
            await("candidate draft") { db { _, app -> app.drafts().size == 1 } }
            fill("충전량 kWh (선택)", "24.5"); fill("비용 원", "12000")
            tap("확인하고 저장")
            db { store, app ->
                assertEquals(24.5, app.confirmedEntries().single().energyAmount!!, 0.0)
                assertEquals(CandidateState.CONFIRMED, store.candidates(app.profile().id).first { it.id == "c" }.state)
                app.cancel(app.confirmedEntries().single().id)
            }
            nav(3, "자동 기록 후보"); tap("충전 아님 · 제외")
            db { store, app -> assertEquals(CandidateState.EXCLUDED, store.candidates(app.profile().id).first { it.id == "c" }.state) }
        }
    }

    @Test fun uiSharingAndUserSwitch() {
        db { _, app -> val d = app.manualDraft(Item.ENGINE_OIL, app.today(), 80000, Reading.Unavailable("test"), "나"); app.confirm(d.id) }
        ActivityScenario.launch(MainActivity::class.java).use {
            parked(); onView(withContentDescription("더보기")).perform(click()); tap("공유 범위"); tap("차량의 모든 로컬 프로필에 공개"); tap("저장")
            tap("사용자 전환"); fill("이름 (예: 엄마)", "가족"); tap("저장")
            nav(4, "일지·비용")
            onView(withText("아직 확정한 기록이 없어요.")).check(matches(isDisplayed()))
            onView(withContentDescription("더보기")).perform(click()); tap("사용자 전환"); fill("이름 (예: 엄마)", "나"); tap("저장")
            nav(4, "일지·비용")
            onView(withContentDescription("${LocalDate.now()} 엔진오일 교환 기록 상세")).check(matches(isDisplayed()))
        }
    }

    @Test fun uiReportFileSaveReopenShareUriAndDelete() {
        val reportRoot = java.io.File(context.filesDir, "reports")
        reportRoot.deleteRecursively() // isolated smoke package only, guarded by @Before
        db { _, app -> val d = app.manualDraft(Item.ENGINE_OIL, app.today(), 80000, Reading.Unavailable("test"), "나"); app.confirm(d.id) }
        ActivityScenario.launch(MainActivity::class.java).use {
            parked(); nav(4, "일지·비용"); tap("정비 리포트 내보내기"); tap("저장"); tap("파일로 저장")
            onView(withText("리포트를 앱에 저장했어요.")).check(matches(isDisplayed()))
            val file = reportRoot.walkTopDown().single { it.isFile && it.extension == "txt" }
            val contents = file.readText(Charsets.UTF_8)
            assertTrue(contents.contains("80,000원"))
            assertFalse(contents.contains("기록 나"))
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
            assertEquals("content", uri.scheme)
            assertEquals(contents, context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            tap("저장한 리포트"); androidx.test.espresso.Espresso.onData(anything()).inAdapterView(isAssignableFrom(android.widget.ListView::class.java)).atPosition(0).perform(click())
            onView(withId(android.R.id.message)).check(matches(withText(contents)))
            tap("삭제")
            assertFalse(file.exists())
            db { _, app -> assertEquals(1, app.confirmedEntries().size) }
        }
    }
}
