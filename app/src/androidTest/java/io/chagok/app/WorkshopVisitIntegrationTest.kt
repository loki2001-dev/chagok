package io.chagok.app

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.companion.CompanionActivity
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** tools/smoke-device.py --workshop-visit streams GPS fixes and real virtual vehicle signals. */
class WorkshopVisitIntegrationTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val shop = ServicePlace("workshop-demo", "차곡 시연 정비소", PlaceKind.REPAIR,
        37.5, 127.0, "에뮬레이터 시험 지점", "합성 장소")

    private fun signal(value: String) =
        instrument.sendStatus(2, Bundle().apply { putString("chagok_signal", value) })

    private fun await(label: String, timeout: Long = 15_000, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!ready()) {
            check(SystemClock.elapsedRealtime() < end) { label }
            Thread.sleep(200)
        }
    }

    @Test fun parkedVisitToSavedRepairAndDashboard() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("workshopVisit") == "true")
        resetSmoke(context)
        val store = SqliteStore(context)
        try {
            val diary = DiaryService(store)
            diary.selectUser("나")
            diary.setPlan(Item.DRIVE_BELT, null, 12, "시연 정비 주기")
            store.replacePlaces(listOf(shop))
            store.saveSetting(PlaceVisitTracker.ENABLED, "true")
            // This scenario is about the workshop question; keep the one-time mileage question out of it.
            diary.offerServiceMileage()
            val car = diary.profile().id
            ActivityScenario.launch(CompanionActivity::class.java).use { app ->
                signal("DRIVE")
                await("주행 신호 미수신") {
                    var moving = false
                    app.onActivity { moving = (it.monitorState.motion.speedMps ?: 0f) > 1f }
                    moving
                }
                signal("WORKSHOP_APPROACH")
                await("주행 중 업체 좌표 미수신") { store.setting("places.status")?.contains(shop.name) == true }
                assertTrue(store.visits(car).isEmpty())
                signal("PARK")
                await("정차 후 방문 관찰 미시작") { store.visits(car).singleOrNull()?.state == VisitState.WATCHING }
                assertTrue(diary.pendingVisits("나").isEmpty())
                await("60초 관측 후에도 방문 후보 없음", 100_000) { diary.pendingVisits("나").size == 1 }
                val visit = diary.pendingVisits("나").single()
                assertTrue(visit.observedSeconds >= 60)
                assertFalse(visit.hasGap)
                assertTrue(diary.confirmedEntries().isEmpty())
                // Stay long enough for the automatic question scheduler to observe READY.
                Thread.sleep(2200)
                app.onActivity { assertEquals("", it.scene.question) }
                signal("DRIVE")
                await("출발 신호 미수신") {
                    var moving = false
                    app.onActivity { moving = (it.monitorState.motion.speedMps ?: 0f) > 1f }
                    moving
                }
                app.onActivity { assertEquals("", it.scene.question) }
                signal("WORKSHOP_EXIT")
                await("정비소 출차 미감지") { store.visits(car).single().exited }
                // Questions are parked-only: nothing is asked while driving away from the workshop.
                Thread.sleep(2200)
                app.onActivity { assertEquals("", it.scene.question) }
                signal("PARK")
                signal("WORKSHOP_QUESTION")
                await("출차 후 다음 정차에서 말풍선 미표시") {
                    var asking = false
                    app.onActivity { asking = it.scene.question == "방금 정비하셨나요?" }
                    asking
                }
                Thread.sleep(2200)
                // Pass final Korean transcripts to the same callback as the on-device speech recognizer.
                app.onActivity { it.receiveAnswer("네"); assertEquals("무엇을 정비하셨나요?", it.scene.question) }
                Thread.sleep(2200)
                app.onActivity {
                    it.receiveAnswer("드라이브 벨트 교체 9만 5천 원")
                    assertEquals("드라이브 벨트 교체 · 95,000원\n이대로 기록할까요?", it.scene.question)
                }
                assertTrue(diary.confirmedEntries().isEmpty())
                Thread.sleep(2200)
                signal("WORKSHOP_SAVE")
                app.onActivity { it.receiveAnswer("네") }
                val entry = diary.confirmedEntries().single()
                assertEquals(visit.id, entry.visitId)
                assertEquals(Item.DRIVE_BELT, entry.item)
                assertEquals(95000L, entry.cost)
                assertEquals(entry.id, store.visits(car).single().linkedEntryId)
                assertEquals(VisitState.CONFIRMED, store.visits(car).single().state)
                assertEquals(0, diary.reviewCount())
                // Observe the transient animation on a frame, without waiting for UI animations to go idle.
                fun receiptShown(): Boolean {
                    var visible = false
                    app.onActivity {
                        val matches = ArrayList<android.view.View>()
                        it.window.decorView.findViewsWithText(matches, Item.DRIVE_BELT.label + " 기록 저장 완료",
                            android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                        visible = matches.any { view -> view.isShown }
                    }
                    return visible
                }
                // Saved while parked, so the receipt animation plays right away.
                await("정차 중 저장된 정비 연출 미표시") { receiptShown() }
                await("정비 연출이 끝나지 않음") { !receiptShown() }
                onView(withContentDescription("차량 일지와 관리 열기")).perform(click())
                onView(withContentDescription("차량 요약 탭")).perform(click())
                val due = entry.performedOn.plusMonths(12).format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
                onView(withText("교체 예정일 $due")).perform(scrollTo()).check(matches(isDisplayed()))
                signal("WORKSHOP_SUMMARY")
                Thread.sleep(2000)
                val row = withContentDescription("${entry.performedOn} ${entry.item.label} 기록 상세")
                onView(row).perform(scrollTo(), click())
                onView(withText("방문 확인 · ${shop.name}")).perform(scrollTo()).check(matches(isDisplayed()))
                assertEquals(1, diary.confirmedEntries().size)
                signal("WORKSHOP_DETAIL")
                Thread.sleep(2000)
            }
        } finally { store.close() }
    }
}
