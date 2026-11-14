package io.chagok.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.companion.CompanionActivity
import io.chagok.app.data.SqliteStore
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
class ParkedDiarySmokeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> db(block: (SqliteStore, DiaryService) -> T): T = SqliteStore(context).let { store ->
        try { block(store, DiaryService(store)) } finally { store.close() }
    }
    private val frameGate=android.os.Handler(android.os.Looper.getMainLooper())
    private val freezeScene=object:Runnable {
        override fun run() {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .filterIsInstance<CompanionActivity>().forEach { it.scene.pause() }
            frameGate.postDelayed(this,100)
        }
    }
    @Before fun isolate() { resetSmoke(context);frameGate.post(freezeScene) }
    @org.junit.After fun releaseFrameGate() { frameGate.removeCallbacks(freezeScene) }
    private fun await(label: String, condition: () -> Boolean) {
        val until=System.currentTimeMillis()+15000
        do { if(condition())return;Thread.sleep(150) } while(System.currentTimeMillis()<until)
        fail("Timed out: $label")
    }
    private fun parked() = await("parked") { runCatching {
        onView(withText("정차 상태가 확인되면 이용할 수 있어요")).check(matches(not(isDisplayed())));true
    }.getOrDefault(false) }
    private fun tap(label: String) {
        if(label in listOf("요약","일지","관리","통계")) {
            // The native AAOS surface may still be moving after Park/recreation.
            // Assert navigation actually landed, not a cost also present on the summary.
            await("selected tab: $label") {
                if(runCatching { onView(withText(label)).check(matches(isSelected()));true }.getOrDefault(false))true
                else { onView(withText(label)).perform(click());false }
            }
            return
        }
        val v=onView(withText(label));if(runCatching{v.check(matches(isDisplayed()))}.isFailure)v.perform(scrollTo());v.perform(click())
    }
    private fun all(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList())
    private fun seed() {
        if (InstrumentationRegistry.getArguments().getString("virtualConsole")=="true") {
            instrument.sendStatus(2,android.os.Bundle().apply { putString("chagok_signal","BASELINE") })
            Thread.sleep(500)
        }
        db { store, app ->
        app.updateProfile("시연 차량 · 시험용 기록", Powertrain.ICE, null)
        fun record(item:Item,date:LocalDate,cost:Long?,km:Double):DiaryEntry {
            val d=app.manualDraft(item,date,cost,Reading.Unavailable("test"),"나")
            app.updateEntry(d.id,date,km,cost,null,"화면 검증용 기록",emptySet())
            assertTrue(app.confirm(d.id) is DiaryService.ConfirmResult.Saved)
            return app.entry(d.id)!!
        }
        for(i in 5 downTo 0) record(Item.REFUEL,app.today().minusMonths(i.toLong()),70000L+i*5000,47000.0-i*850)
        record(Item.ENGINE_OIL,app.today().minusMonths(12),85000,37200.0)
        record(Item.TIRE_ROTATION,app.today().minusMonths(5),30000,43000.0)
        record(Item.WASHER_REFILL,app.today(),10000,47200.0)
        record(Item.WIPER_CHECK,app.today(),null,47200.0)
        app.setPlan(Item.ENGINE_OIL,10000,12,"테스트용 차량 매뉴얼")
        app.setPlan(Item.TIRE_ROTATION,10000,12,"테스트용 차량 매뉴얼")
        val now=Instant.now()
        store.saveCandidate(DetectionCandidate("review-one",app.profile().id,EnergyKind.FUEL,Observation(20.0,now.minusSeconds(6000)),Observation(80.0,now.minusSeconds(5940)),CandidateState.PENDING,false))
        app.draftFromCandidate("review-one","나",Reading.Unavailable("test"))
        }
    }

    @Test fun summaryStatisticsFiltersDetailsAndSavedState() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            parked()
            onView(withText("1건만 확인해 주세요")).check(matches(isDisplayed()))
            tap("통계")
            onView(withText("80,000원")).check(matches(isDisplayed()))
            onView(withText("금액 미입력이 있어 전월 비교를 보류해요.")).check(matches(isDisplayed()))
            tap("일지")
            onView(withContentDescription("일지 필터 주유·충전")).perform(click())
            onView(withContentDescription("${LocalDate.now()} 주유 기록 상세")).perform(scrollTo(),click())
            onView(withText("기록 상세")).check(matches(isDisplayed()))
            scenario.recreate();parked()
            // Pleos can reparent the surface just after recreation; wait for the requested dialog.
            await("edit dialog after recreation") {
                if (runCatching { onView(withHint("비용 원")).check(matches(isDisplayed())); true }.getOrDefault(false)) true
                else { tap("수정하기"); false }
            }
            onView(withHint("비용 원")).perform(scrollTo(),replaceText("65000"),closeSoftKeyboard())
            tap("저장")
            db { _,app->assertEquals(75000L,app.ledger(YearMonth.now()).total) }
            onView(withContentDescription("이전 화면")).perform(click())
            onView(withContentDescription("일지 필터 주유·충전")).check(matches(isSelected()))
            tap("통계")
            onView(withText("75,000원")).check(matches(isDisplayed()))
            onView(withContentDescription("이전 달")).perform(click())
            scenario.recreate();parked()
            val month=YearMonth.now().minusMonths(1)
            onView(withText("${month.year}년 ${month.monthValue}월")).check(matches(isDisplayed()))
        }
    }

    @Test fun supportingPagesFitSmallPaneFullWidthAndNightMode() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            parked()
            scenario.onActivity { activity ->
                val density=activity.resources.displayMetrics.density
                for((width,height) in listOf(320 to 800,841 to 1210,1682 to 1210,1100 to 670)) {
                    val config=Configuration(activity.resources.configuration).apply { screenWidthDp=width;screenHeightDp=height }
                    activity.onConfigurationChanged(config)
                    for((label,name) in listOf("차량 요약" to "summary","차량 일지" to "diary","소모품 관리" to "maintenance","비용 통계" to "statistics")) {
                        val host=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        all(host).single { it.contentDescription=="$label 탭" }.performClick()
                        val w=(width*density).toInt();val h=(height*density).toInt()
                        host.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));host.layout(0,0,w,h)
                        host.viewTreeObserver.dispatchOnPreDraw()
                        for(text in all(host).filterIsInstance<TextView>().filter { it.visibility==View.VISIBLE && it.isShown && it.width>0 }) {
                            val layout=text.layout?:continue
                            for(line in 0 until layout.lineCount) {
                                assertEquals("No ellipsis: ${text.text}",0,layout.getEllipsisCount(line))
                                assertTrue("Text fits $width dp: ${text.text}",layout.getLineMax(line)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+2)
                            }
                        }
                        val tabs=all(host).filter { it.contentDescription?.endsWith(" 탭")==true }
                        assertEquals(4,tabs.size)
                        tabs.forEach { tab ->
                            assertTrue("Tab fits viewport",tab.width>0&&tab.height>=64*density)
                            val bounds=android.graphics.Rect(0,0,tab.width,tab.height)
                            (host as ViewGroup).offsetDescendantRectToMyCoords(tab,bounds)
                            assertTrue("All four tabs stay on screen at $width x $height",bounds.left>=0&&bounds.top>=0&&bounds.right<=w&&bounds.bottom<=h)
                        }
                        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);host.draw(Canvas(bitmap))
                        File(context.filesDir,"parked-$name-${width}x$height.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                    }
                }
                val night=Configuration(activity.resources.configuration).apply { screenWidthDp=841;uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES }
                activity.onConfigurationChanged(night)
                val host=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val w=(841*density).toInt();val h=(1210*density).toInt()
                host.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));host.layout(0,0,w,h);host.viewTreeObserver.dispatchOnPreDraw()
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);host.draw(Canvas(bitmap))
                File(context.filesDir,"parked-statistics-night.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        }
    }

    @Test fun parkedCompanionStaysMainAndOpensDiaryOnlyWhenRequested() {
        ActivityScenario.launch(CompanionActivity::class.java).use { scenario ->
            // Query and click the native controls on the main thread. A continuously drawing
            // scene does not provide Espresso's global idle boundary on every emulator GPU.
            await("parked diary shortcut") {
                var ready=false
                scenario.onActivity { a -> a.scene.pause();ready=all(a.window.decorView).any { it.contentDescription=="차량 일지와 관리 열기" && it.isShown && it.isEnabled } }
                ready
            }
            Thread.sleep(1200)
            scenario.onActivity { a ->
                assertFalse(a.isFinishing);assertTrue(a.scene.isShown)
                all(a.window.decorView).single { it.contentDescription=="차량 일지와 관리 열기" }.performClick()
            }
            parked();onView(withText("차량 요약")).check(matches(isDisplayed()))
            instrument.runOnMainSync {
                val main=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()
                all(main.window.decorView).single { it.contentDescription=="주행 화면으로 돌아가기" }.performClick()
            }
            await("returned companion") {
                var ready=false
                instrument.runOnMainSync {
                    val companion=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<CompanionActivity>().firstOrNull()
                    companion?.let { a -> a.scene.pause();ready=all(a.window.decorView).any { it.contentDescription=="차량 일지와 관리 열기" && it.isShown } }
                };ready
            }
        }
    }

    @Test fun maintenanceSettingsAndRecordShortcutUseTheSelectedItem() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            parked();tap("관리")
            fun settings() = onView(withContentDescription("엔진오일 교환 관리 설정")).perform(scrollTo(),click())
            settings();tap("주기 설정")
            onView(withHint("거리 주기 km")).perform(scrollTo(),replaceText("20000"),closeSoftKeyboard())
            onView(withHint("기간 주기 개월")).perform(scrollTo(),replaceText("24"),closeSoftKeyboard())
            tap("저장")
            db { _,app->
                val status=app.schedules(47200.0).single { s->s.plan.item==Item.ENGINE_OIL }
                assertEquals(57200.0,status.nextKm!!,.0);assertEquals(ScheduleState.UPCOMING,status.state)
            }
            settings();tap("7일 미루기")
            settings();tap("알림 끄기")
            db { _,app->val plan=app.schedules(47200.0).single { s->s.plan.item==Item.ENGINE_OIL }.plan
                assertEquals(app.today().plusDays(7),plan.snoozedUntil);assertFalse(plan.notificationsOn) }
            onView(withContentDescription("엔진오일 교환 기록 추가")).perform(scrollTo(),click())
            db { _,app->assertEquals(1,app.drafts().count { d->d.item==Item.ENGINE_OIL }) }
            onView(withText("확인하고 저장")).check(matches(isDisplayed()))
        }
    }
}
