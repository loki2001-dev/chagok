package io.chagok.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.companion.CompanionHarnessActivity
import io.chagok.app.companion.MotionSnapshot
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

/** Visit every destination and app-owned dialog; save native view captures for visual review. */
@RunWith(AndroidJUnit4::class)
class UiReviewSmokeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    @Before fun reset() { resetSmoke(context) }
    private fun <T> db(block:(SqliteStore,DiaryService)->T):T = SqliteStore(context).let { store -> try { block(store,DiaryService(store)) } finally { store.close() } }
    private fun seed() = db { store, app ->
        app.updateProfile("아이오닉 · 디자인 검증",Powertrain.ICE,null)
        for(i in 5 downTo 0) {
            val d=app.manualDraft(if(i==0)Item.ENGINE_OIL else Item.REFUEL,app.today().minusMonths(i.toLong()),80000L+i*5000,Reading.Unavailable("test"),"나")
            app.updateEntry(d.id,d.performedOn,47200.0-i*1000,d.cost,null,"정기 점검과 함께 교환했어요.",emptySet());assertTrue(app.confirm(d.id) is DiaryService.ConfirmResult.Saved)
        }
        app.setPlan(Item.ENGINE_OIL,10000,12,"차량 매뉴얼 · 시험용 설정")
        val now=Instant.now();store.saveCandidate(DetectionCandidate("review-ui",app.profile().id,EnergyKind.FUEL,Observation(20.0,now.minusSeconds(5400)),Observation(80.0,now.minusSeconds(5300)),CandidateState.PENDING,false))
    }
    private fun tap(label:String) { val v=onView(withText(label));if(runCatching{v.check(matches(isDisplayed()))}.isFailure)v.perform(scrollTo());v.perform(click()) }
    private fun more() = onView(withContentDescription("더보기")).perform(click())
    private fun parked() {
        val until=System.currentTimeMillis()+12000
        while(System.currentTimeMillis()<until) {
            if(runCatching{onView(withText("정차 상태가 확인되면 이용할 수 있어요")).check(matches(not(isDisplayed())));true}.getOrDefault(false))return
            Thread.sleep(150)
        };fail("Parked UI unavailable")
    }
    private fun all(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList())
    private fun capture(view:View,name:String) {
        assertTrue("Capture has bounds",view.width>0&&view.height>0)
        view.viewTreeObserver.dispatchOnPreDraw()
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        File(context.filesDir,"review-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun page(s:ActivityScenario<MainActivity>,name:String) = s.onActivity { a ->
        val original=Configuration(a.resources.configuration).apply { if(name.startsWith("night-"))uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES }
        for(width in listOf(841,320)) {
            a.onConfigurationChanged(Configuration(original).apply { screenWidthDp=width;screenHeightDp=1210 })
            val host=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0);val density=a.resources.displayMetrics.density
            val w=(width*density).toInt();val h=((if(width==320)800 else 1210)*density).toInt()
            host.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));host.layout(0,0,w,h)
            capture(host,"$name-$width")
            all(host).filterIsInstance<android.widget.TextView>().filter { it.isShown && it.width>0 }.forEach { text ->
                assertTrue("Explicit readable font: ${text.text}",text.typeface.weight>=500)
                val layout=text.layout ?: return@forEach
                for(line in 0 until layout.lineCount) assertTrue("Text fits $width: ${text.text}",layout.getLineMax(line)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+2)
            }
            if(width==841) all(host).filterIsInstance<android.widget.ScrollView>().firstOrNull()?.getChildAt(0)?.let { capture(it,"$name-full") }
        }
        a.onConfigurationChanged(original)
    }
    private fun popup(title:String,name:String) { onView(withText(title)).check { view,error -> if(error!=null)throw error;capture(view.rootView,name) } }

    @Test fun everyParkedPageAndDialog() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            parked();page(s,"summary")
            tap("일지");page(s,"diary")
            onView(withContentDescription("${LocalDate.now()} 엔진오일 교환 기록 상세")).perform(scrollTo(),click());page(s,"detail")
            tap("수정하기");popup("엔진오일 교환 기록 수정","edit")
            tap("날짜: ${LocalDate.now()}");onView(isAssignableFrom(android.widget.DatePicker::class.java)).check { view,error->if(error!=null)throw error;capture(view.rootView,"date-picker") };pressBack()
            tap("기록 취소");popup("기록을 취소하면 비용·일정·도장에서 빠져요.","cancel-confirm");tap("돌아가기");tap("취소")
            tap("관리");page(s,"maintenance")
            onView(withContentDescription("엔진오일 교환 관리 설정")).perform(scrollTo(),click());popup("주기 설정","maintenance-actions");tap("주기 설정");popup("엔진오일 교환 주기","interval");tap("취소")
            tap("통계");page(s,"statistics")
            more();page(s,"more")
            tap("차량 설정");popup("차량 설정","vehicle");tap("취소")
            tap("사용자 전환");popup("공용 일지 사용자","user");tap("취소")
            tap("공유 범위");popup("내 기록 공유 범위","sharing");tap("취소")
            tap("기록 추가");page(s,"input")
            tap("항목 골라 입력");popup("정비 항목","item-picker");pressBack()
            onView(withHint("예: 엔진오일 교환 8만 원")).perform(scrollTo(),replaceText("타이어 위치 교환했어. 3만 원"),closeSoftKeyboard());tap("해석")
            Thread.sleep(600);page(s,"draft")
            more();onView(withText(startsWith("확인할 기록 "))).perform(click());page(s,"inbox")
            more();tap("관리 도감");page(s,"learning")
            more();tap("정비 리포트 내보내기");popup("정비 리포트 범위","report-range");tap("저장");popup("리포트 미리보기","report-preview");tap("파일로 저장")
            tap("저장한 리포트");popup("내가 저장한 리포트","reports");androidx.test.espresso.Espresso.onData(anything()).inAdapterView(isAssignableFrom(android.widget.ListView::class.java)).atPosition(0).perform(click());popup("파일 공유","saved-report-detail");tap("닫기")
        }
    }

    @Test fun emptyPagesAndNightMode() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            parked();page(s,"empty-summary");tap("일지");page(s,"empty-diary");tap("통계");page(s,"empty-statistics")
            s.onActivity { a -> a.onConfigurationChanged(Configuration(a.resources.configuration).apply { uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES }) }
            page(s,"night-statistics");more();page(s,"night-more");tap("기록 추가");page(s,"night-input")
        }
    }

    @Test fun companionCollectionAndMenus() {
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { s ->
            s.onActivity { a ->a.inject(MotionSnapshot(speedMps=0f,restricted=false,animationAllowed=true));a.scene.pause();all(a.window.decorView).filterIsInstance<android.widget.TextView>().single { it.text.toString()=="친구 0/10" }.performClick();assertTrue(a.showingCollection) }
            onView(withText("만난 친구들")).check { view,error->if(error!=null)throw error;capture(view.rootView,"collection") }
            s.onActivity { a -> all(a.window.decorView).filterIsInstance<android.widget.TextView>().single { it.text.toString()=="주행으로" }.performClick();a.scene.pause();all(a.window.decorView).filterIsInstance<android.widget.TextView>().single { it.text.toString()=="메뉴" }.performClick() };popup("차량 스킨","companion-menu")
            tap("차량 스킨");popup("차량 스킨","skins");tap("닫기")
        }
    }
}
