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
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.chagok.app.companion.*
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import io.chagok.app.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class LandscapeUiSmokeTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private val clear=MotionSnapshot(speedMps=15f,restricted=false,animationAllowed=true)
    @Before fun reset()=resetSmoke(context)
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun capture(v:View,w:Int,h:Int,name:String?=null) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        v.viewTreeObserver.dispatchOnPreDraw()
        val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(Canvas(bmp))
        if(name!=null)File(context.filesDir,"landscape-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG,100,it) }
        bmp.recycle()
    }

    @Test fun summaryShowsMaintenanceUrgencyAndKeepsCardsReadableAtEveryWidth() {
        val evidence=JSONArray()
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { s -> s.onActivity { a ->
            a.scene.pause()
            val store=SqliteStore(context)
            try {
                val diary=DiaryService(store)
                val today = diary.today()
                for ((item, date) in listOf(
                    Item.BRAKE_PAD_CHECK to today.minusMonths(12).minusDays(1),
                    Item.WASHER_REFILL to today.plusDays(10).minusMonths(12),
                    Item.TIRE_ROTATION to today.minusMonths(6),
                    Item.CABIN_FILTER to today.minusMonths(8),
                    Item.BATTERY_12V_REPLACE to today.minusMonths(3),
                )) {
                    store.saveEntry(DiaryEntry("summary-$item", diary.profile().id, item, date,
                        Instant.now(), null, null, status = EntryStatus.CONFIRMED, author = "나"))
                    diary.setPlan(item, null, 12, "시연용 주기")
                }
                for(pending in listOf(false,true)) {
                    if(pending)diary.manualDraft(Item.REFUEL,diary.today(),null,Reading.Unavailable("test"),"나")
                    for(width in listOf(320,841,1682)) for(fontScale in listOf(1f,1.3f)) {
                        val ctx=a.createConfigurationContext(Configuration(a.resources.configuration).apply { this.fontScale=fontScale;screenWidthDp=width })
                        val ui=Ui(ctx).apply { availableWidthDp=width }
                        val drive = AutoDiaryView(ui, store, diary, "나").today {}
                        var selected: Item? = null
                        val page=DiaryDashboard(ctx,ui,diary).home(47200.0,drive,{ item, _ -> selected = item },{},{},{},{},{}).apply { setBackgroundColor(Palette.bg) }
                        val w=ui.dp(width)
                        page.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                        capture(page,w,page.measuredHeight,if(fontScale==1f)"summary-${if(pending)"pending" else "empty"}-$width" else null)
                        val labels = all(page).filterIsInstance<TextView>()
                        assertEquals(Palette.danger, labels.single { it.text == "100% · 점검 필요" }.currentTextColor)
                        assertEquals(Palette.danger, labels.single { it.text.endsWith("% · 임박") }.currentTextColor)
                        assertTrue(labels.any { it.text.startsWith("보충 예정일 ") })
                        assertTrue(labels.any { it.text.endsWith("% · 예정") && it.currentTextColor == Palette.accent })
                        assertTrue(labels.any { it.text == "주기 미설정 · 4개" })
                        assertTrue(labels.any { it.text == Item.BATTERY_12V_REPLACE.label })
                        assertTrue(labels.any { it.text == Item.DRIVE_BELT.label })
                        val tireRow = all(page).single { it.contentDescription == "${Item.TIRE_REPLACE.label} 주기 수정" }
                        assertTrue(tireRow.performClick())
                        assertEquals(Item.TIRE_REPLACE, selected)
                        val review=all(page).single { it.tag=="summary-review-card" };val mileage=all(page).single { it.tag=="summary-mileage-card" }
                        assertTrue(review.height>0);assertEquals("Cards at $width / font $fontScale",review.height,mileage.height)
                        if(width>=700)assertEquals(review.top,mileage.top) else assertTrue(mileage.top>=review.bottom)
                        for(card in listOf(review,mileage))for(text in all(card).filterIsInstance<TextView>()) {
                            val layout=text.layout!!
                            assertTrue("All lines visible: ${text.text}",layout.height<=text.height-text.compoundPaddingTop-text.compoundPaddingBottom)
                            for(line in 0 until layout.lineCount)assertTrue(layout.getLineMax(line)<=text.width-text.compoundPaddingLeft-text.compoundPaddingRight+2)
                        }
                        evidence.put(JSONObject().put("width",width).put("fontScale",fontScale).put("pending",pending).put("reviewHeight",review.height).put("mileageHeight",mileage.height))
                    }
                }
            } finally { store.close() }
        } }
        File(context.filesDir,"landscape-card-evidence.json").writeText(evidence.toString(2))
    }

    @Test fun bothSkinsUseFarAndNearLanesWithSignalsRainBoostAndQuestions() {
        val evidence=JSONArray()
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { s -> s.onActivity { a ->
            val scene=a.scene;scene.pause()
            for((w,h) in listOf(800 to 400,1682 to 970,1400 to 430))for(skin in CarSkin.entries) {
                scene.skin=skin;scene.question="";scene.begin(Encounters.find("cyclist")!!,true)
                capture(scene,w,h)
                val poses=mutableMapOf<Int,CompanionSceneView.RenderState>()
                for(turn in listOf(-1,0,1)) {
                    a.inject(clear.copy(turn=turn,rain=.8f,acceleration=3.5f))
                    repeat(45){scene.advance(.05f)}
                    capture(scene,w,h,"${skin.name.lowercase()}-${w}x$h-turn$turn")
                    val p=scene.renderState;poses[turn]=p
                    assertTrue(p.landscape);assertEquals(0f,p.angle,0f);assertEquals(.8f,p.rain,0f)
                    assertEquals(skin==CarSkin.RACER,p.boost>.9f)
                    assertTrue(p.carY-p.carHeight/2>scene.sceneLayout.roadTop*.8f)
                    assertTrue(p.groundY<scene.sceneLayout.roadBottom)
                    if(turn!=0) {
                        val states=mutableSetOf<Boolean>()
                        repeat(20){scene.advance(.05f);capture(scene,w,h);states+=scene.renderState.signalOn}
                        assertEquals(setOf(true,false),states)
                    }
                    evidence.put(JSONObject().put("skin",skin.name).put("width",w).put("height",h).put("turn",turn).put("carX",p.carX).put("groundY",p.groundY).put("carWidth",p.width))
                }
                assertTrue(poses.getValue(-1).groundY<poses.getValue(0).groundY&&poses.getValue(0).groundY<poses.getValue(1).groundY)
                assertTrue(poses.getValue(-1).width<poses.getValue(1).width)
                scene.question="충전비,\n얼마였어요?";scene.questionHint="금액 또는 ‘나중에’라고 말해주세요"
                capture(scene,w,h,"${skin.name.lowercase()}-${w}x$h-question")
                scene.question=""
                scene.begin(Encounters.find("farmer")!!,true)
                capture(scene,w,h,"${skin.name.lowercase()}-${w}x$h-country")
            }
        } }
        File(context.filesDir,"landscape-lane-evidence.json").writeText(evidence.toString(2))
    }

    @Test fun resizingKeepsEncounterProgressAndRestrictionsFreezeHorizontalMotion() {
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { s -> s.onActivity { a ->
            val scene=a.scene;scene.pause();a.inject(clear)
            val collected=mutableListOf<String>();scene.collect={collected+=it.scene.id;true}
            capture(scene,320,800);scene.begin(Encounters.find("calico")!!,true)
            repeat(15){scene.advance(.05f)}
            val before=scene.director.active!!;val fraction=(before.y+235)/scene.director.viewportHeight
            capture(scene,1682,970)
            assertSame(before,scene.director.active);assertEquals(fraction,(before.y+235)/scene.director.viewportHeight,.0001f)
            repeat(130){scene.advance(.05f)};capture(scene,1682,970)
            assertEquals(listOf("calico"),collected)
            capture(scene,320,800);assertFalse(scene.renderState.landscape)
            capture(scene,1682,970);assertTrue(scene.renderState.landscape)
            val travel=scene.renderState.horizontalTravel
            a.inject(clear.copy(turn=1,rain=1f,acceleration=4f,restricted=true,animationAllowed=false))
            repeat(60){scene.advance(.05f)};capture(scene,1682,970,"restricted")
            assertEquals(travel,scene.renderState.horizontalTravel,0.0);assertFalse(scene.renderState.signalOn)
            assertEquals(0f,scene.renderState.rain,0f);assertEquals(0f,scene.renderState.boost,0f);assertNull(scene.renderState.encounter)
            a.inject(clear);repeat(60){scene.advance(.05f)};capture(scene,1682,970)
            assertTrue(scene.renderState.horizontalTravel>travel);assertEquals(listOf("calico"),collected)
        } }
    }

    @Test fun allTenLandscapeEncountersRenderAndCollectExactlyOnce() {
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { s -> s.onActivity { a ->
            val scene=a.scene;scene.pause();a.inject(clear)
            val collected=mutableListOf<String>();scene.collect={collected+=it.scene.id;true}
            capture(scene,800,400)
            for(e in Encounters.all) {
                scene.begin(e,true);repeat(40){scene.advance(.05f)}
                capture(scene,800,400,"encounter-${e.id}")
                repeat(140){scene.advance(.05f)}
            }
            assertEquals(Encounters.all.map { it.id },collected)
        } }
    }
}
