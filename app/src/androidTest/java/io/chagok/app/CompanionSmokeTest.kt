package io.chagok.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
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
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class CompanionSmokeTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    @Before fun isolate()=resetSmoke(context)
    private fun <T> db(block:(SqliteStore,DiaryService)->T):T {
        val s=SqliteStore(context);return try{block(s,DiaryService(s))}finally{s.close()}
    }
    private fun buttons(v:View):List<Button> = if(v is Button)listOf(v) else if(v is ViewGroup)(0 until v.childCount).flatMap{buttons(v.getChildAt(it))} else emptyList()
    private fun button(a:CompanionActivity,text:String)=buttons(a.window.decorView).single{it.text.toString()==text}
    private fun draw(scene:CompanionSceneView,name:String?=null,w:Int=320,h:Int=500):Bitmap {
        scene.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));scene.layout(0,0,w,h)
        return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{bmp->
            scene.draw(Canvas(bmp));if(name!=null)File(context.filesDir,"companion-$name.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    private val clear get()=MotionSnapshot(speedMps=0f,restricted=false,animationAllowed=true)
    private fun menu(option:String) {
        val end=System.currentTimeMillis()+10000
        do {
            if(runCatching{onView(withText(option)).check(matches(isDisplayed()));true}.getOrDefault(false)){
                onView(withText(option)).perform(click());return
            }
            // AAOS may reparent the surface between scenarios and drop the first injected tap.
            runCatching{onView(withText("메뉴")).perform(click())}
            Thread.sleep(200)
        }while(System.currentTimeMillis()<end)
        fail("Menu option never appeared: $option")
    }

    @Test fun collectionIsAtomicIdempotentAndSeparatedByVehicleAndUser() {
        val now=Instant.now()
        db{s,_->
            assertTrue(s.recordEncounter("v1","A","calico","token",now))
            assertFalse(s.recordEncounter("v1","A","calico","token",now))
            assertTrue(runCatching{s.transaction{s.recordEncounter("v1","A","cyclist","rolled-back",now);error("rollback")}}.isFailure)
            assertEquals(1,s.encounters("v1","A").size)
        }
        db{s,_->
            assertEquals(1,s.encounters("v1","A").single().count)
            s.recordEncounter("v1","A","calico","second",now.plusSeconds(1))
            assertEquals(2,s.encounters("v1","A").single().count)
            assertEquals(now,s.encounters("v1","A").single().first)
            assertTrue(s.encounters("v1","B").isEmpty());assertTrue(s.encounters("v2","A").isEmpty())
            assertTrue(s.recordEncounter("v1","B","calico","token",now))
            assertTrue(s.recordEncounter("v2","A","calico","token",now))
            assertTrue(s.recordEncounter("v1","A","cyclist","rolled-back",now))
        }
    }
    @Test fun tenNativeScenesCollectOnceRestoreAndPreviewNeverCollects() {
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use{scenario->
            scenario.onActivity{a->
                a.inject(clear.copy(speedMps=12f));a.scene.pause()
                Encounters.all.forEach{e->
                    a.scene.begin(e,true);repeat(20){a.scene.advance(.05f)}
                    val bmp=draw(a.scene,e.id);assertTrue(bmp.getPixel(160,400)!=bmp.getPixel(5,400));bmp.recycle()
                    repeat(100){a.scene.advance(.05f)}
                }
                db{s,d->assertEquals(10,s.encounters(d.profile().id,"나").size);assertTrue(s.encounters(d.profile().id,"나").all{it.count==1})}
                a.scene.begin(Encounters.all.first(),true);repeat(90){a.scene.advance(.05f)}
                db{s,d->assertEquals(2,s.encounters(d.profile().id,"나").single{it.id=="calico"}.count)}
                a.inject(clear);button(a,"친구 10/10").performClick();assertTrue(a.showingCollection)
            }
            instrument.waitForIdleSync()
            instrument.uiAutomation.takeScreenshot().let{bmp->File(context.filesDir,"companion-collection.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}
            scenario.onActivity{a->button(a,"주행으로").performClick();a.scene.pause();a.scene.preview=true;a.scene.begin(Encounters.all.first(),true);repeat(150){a.scene.advance(.05f)}}
            db{s,d->assertEquals(2,s.encounters(d.profile().id,"나").single{it.id=="calico"}.count)}
            scenario.recreate()
            scenario.onActivity{a->a.inject(clear);a.scene.pause();assertEquals("친구 10/10",button(a,"친구 10/10").text);assertTrue(a.scene.preview)}
        }
    }
    @Test fun bothSkinsShiftWithoutRotatingBlinkAndRespectRestrictionsAndPause() {
        var running:CompanionHarnessActivity?=null
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use{scenario->
            scenario.onActivity{a->
                a.scene.pause()
                CarSkin.entries.forEach{skin->
                    a.scene.skin=skin;a.inject(clear.copy(speedMps=15f,turn=-1,rain=.8f,acceleration=3.4f))
                    val blinks=mutableSetOf<Boolean>()
                    repeat(40){a.scene.advance(.05f);draw(a.scene).recycle();blinks+=a.scene.renderState.signalOn}
                    assertEquals(setOf(false,true),blinks);assertTrue(a.scene.renderState.carX<125f)
                    assertEquals(0f,a.scene.renderState.angle,0f);assertTrue(a.scene.renderState.width<100f)
                    assertEquals(.8f,a.scene.renderState.rain,0f)
                    assertEquals(skin==CarSkin.RACER,a.scene.renderState.boost>.9f)
                    a.scene.question="충전비,\n얼마였어요?";a.scene.questionHint="금액 또는 ‘나중에’라고 말해주세요"
                    draw(a.scene,skin.name.lowercase()+"-rain-question").recycle()
                    a.inject(clear.copy(speedMps=15f,turn=1));repeat(40){a.scene.advance(.05f)};draw(a.scene).recycle()
                    assertTrue(a.scene.renderState.carX>195f)
                    draw(a.scene,skin.name.lowercase()+"-wide",800,500).recycle()
                }
                val before=a.scene.travel
                a.inject(clear.copy(speedMps=15f,turn=1,rain=1f,restricted=true,animationAllowed=false))
                repeat(100){a.scene.advance(.05f)};draw(a.scene,"restricted").recycle()
                assertEquals(before,a.scene.travel,0f);assertEquals(0f,a.scene.renderState.rain,0f)
                assertEquals(0f,a.scene.renderState.boost,0f);assertFalse(a.scene.renderState.signalOn)
                assertFalse(button(a,"메뉴").isEnabled);assertNull(a.scene.renderState.encounter)
                running=a;a.inject(clear.copy(speedMps=10f));a.scene.resume()
            }
            Thread.sleep(150)
            var position=0f
            // Start an opaque Activity directly: moveToState first waits for global idle,
            // which a continuous renderer may never provide on the emulator's GPU.
            instrument.runOnMainSync { running!!.startActivity(android.content.Intent(running,CompanionPauseTargetActivity::class.java)) }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity{position=it.scene.travel}
            Thread.sleep(350)
            scenario.onActivity{assertEquals(position,it.scene.travel,0f)}
        }
    }
    @Test fun nativeQuestionRequiresConfirmationAndDefersOnBackground() {
        var candidate=""
        fun seed(id:String) {db{s,d->d.updateProfile("EV",Powertrain.EV,null);val now=Instant.now();s.saveCandidate(DetectionCandidate(id,d.profile().id,EnergyKind.SOC,Observation(20.0,now.minusSeconds(60)),Observation(80.0,now),CandidateState.PENDING,false));candidate=id}}
        seed("confirm")
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use{scenario->
            scenario.onActivity{a->a.inject(clear);a.scene.pause();assertTrue(a.beginQuestion(candidate));a.receiveAnswer("1만 2천 원");assertEquals(EnergyConversation.Stage.CONFIRM,a.conversation.stage);draw(a.scene,"confirm").recycle()}
            db{_,d->assertEquals(0L,d.ledger(YearMonth.now()).total)}
            scenario.onActivity{it.receiveAnswer("맞아")}
            db{_,d->assertEquals(12000L,d.ledger(YearMonth.now()).total);assertEquals(1,d.confirmedEntries().size)}
            seed("defer")
            scenario.onActivity{it.conversation.clear();assertTrue(it.beginQuestion(candidate));it.receiveAnswer("2만원")}
            scenario.moveToState(Lifecycle.State.CREATED)
            db{_,d->assertEquals(12000L,d.ledger(YearMonth.now()).total);assertEquals(1,d.drafts().size)}
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity{assertEquals(EnergyConversation.Stage.FINISHED,it.conversation.stage)}
            // The moving-question exception belongs only to the presentation subclass.
            scenario.onActivity { a ->
                a.inject(clear); a.conversation.clear(); assertTrue(a.beginQuestion(candidate))
                a.inject(clear.copy(speedMps=10f, restricted=true, animationAllowed=false))
                assertEquals(EnergyConversation.Stage.FINISHED, a.conversation.stage)
            }
            db { _, d -> assertEquals(12000L, d.ledger(YearMonth.now()).total); assertEquals(1, d.drafts().size) }
        }
    }
    @Test fun menusPersistSkinAndLayoutFillsSmallPortraitAndWideWindows() {
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use{s->
            s.onActivity{it.inject(clear);it.scene.pause()}
            menu("차량 스킨");onView(withText("현대 세단")).perform(click())
            s.recreate()
            s.onActivity{a->
                a.inject(clear);a.scene.pause();assertEquals(CarSkin.HYUNDAI,a.scene.skin)
                val host=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                val density=a.resources.displayMetrics.density
                for((widthDp,heightDp) in listOf(320 to 800,480 to 1000,560 to 800,841 to 1210,1682 to 1210,1100 to 670,1400 to 670)){
                    val w=(widthDp*density).toInt();val h=(heightDp*density).toInt()
                    host.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));host.layout(0,0,w,h)
                    // TextView settles its horizontal scroll in pre-draw after a window resize.
                    host.viewTreeObserver.dispatchOnPreDraw()
                    val root=host.getChildAt(0) as ViewGroup;val header=root.getChildAt(0) as ViewGroup
                    assertEquals(w,root.width);assertEquals(0,root.left);assertEquals(w,a.scene.width)
                    for(i in 0 until header.childCount){
                        val child=header.getChildAt(i) as TextView
                        assertTrue("Header fits $widthDp dp",child.left>=0&&child.right<=header.width)
                        assertEquals("Header label stays on one line",1,child.lineCount)
                        assertEquals("Header label has no ellipsis",0,child.layout.getEllipsisCount(0))
                        assertTrue("Complete header text fits $widthDp dp",
                            child.paint.measureText(child.text.toString())<=child.width-child.compoundPaddingLeft-child.compoundPaddingRight+1)
                        val textLeft=child.compoundPaddingLeft+child.layout.getLineLeft(0)-child.scrollX
                        val textRight=child.compoundPaddingLeft+child.layout.getLineRight(0)-child.scrollX
                        assertTrue("Rendered header text is not clipped",textLeft>=0&&textRight<=child.width+1)
                    }
                    val status=root.getChildAt(1) as TextView;val footer=root.getChildAt(3) as TextView
                    assertTrue(status.textSize/a.resources.displayMetrics.scaledDensity>=20f)
                    assertTrue(footer.textSize/a.resources.displayMetrics.scaledDensity>=20f)
                    assertTrue(footer.bottom<=h);assertTrue(footer.top>=a.scene.bottom+status.bottom)
                    assertTrue(a.scene.height>300*density)
                    val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);host.draw(Canvas(bmp))
                    File(context.filesDir,"companion-layout-${widthDp}x$heightDp.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()
                }
                host.requestLayout()
                val config=Configuration(a.resources.configuration).apply{uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES}
                a.onConfigurationChanged(config);a.scene.pause()
                assertEquals(io.chagok.app.ui.Palette.text,button(a,"메뉴").currentTextColor)
                a.onConfigurationChanged(a.resources.configuration);a.scene.pause()
            }
            menu("주행 연출 미리보기")
            s.onActivity{assertTrue(it.scene.preview);assertTrue(button(it,"차량 일지 · 관리  ›").isShown)}
            menu("다음 만남")
            s.onActivity{assertEquals("cyclist",it.scene.director.active?.scene?.id);it.inject(clear.copy(speedMps=4f,restricted=true,animationAllowed=false));assertFalse(it.scene.preview)}
        }
    }
    @Test fun roadLayersFillEveryAspectAndKeepBothCarsInsideTheKerbs() {
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.scene.pause()
                val blank=android.graphics.Color.parseColor("#1b2326")
                a.inject(clear)
                // At 1:1 source pixels, compare the overhanging tail/cart to the original artwork.
                for (id in listOf("calico","florist")) {
                    // Set the target viewport before placing the source-pixel reference event.
                    // Resizing an active scene deliberately preserves its relative progress.
                    draw(a.scene,null,480,750).recycle()
                    a.scene.begin(Encounters.find(id)!!,true)
                    val actual=draw(a.scene,null,480,750);val original=a.scene.artwork(id)
                    for (x in 106..126) for (y in 325..395) {
                        val expected=original.getPixel(x,y);val rendered=actual.getPixel(x,y-90)
                        for (shift in listOf(0,8,16)) assertTrue("Preserve $id beyond the kerb",
                            kotlin.math.abs(((expected shr shift) and 255)-((rendered shr shift) and 255))<=3)
                    }
                    actual.recycle()
                }
                for ((w,h) in listOf(320 to 800,841 to 970,1682 to 970,1400 to 430)) {
                    for (encounter in listOf("cyclist","ducklings")) {
                        a.scene.begin(Encounters.find(encounter)!!,true)
                        for (skin in CarSkin.entries) for (turn in listOf(-1,1)) {
                            a.scene.skin=skin;a.inject(clear.copy(speedMps=12f,turn=turn))
                            repeat(35){a.scene.advance(.05f)}
                            val bmp=draw(a.scene,null,w,h)
                            for (x in listOf(1,w/2,w-2)) for (y in listOf(1,h/2,h-2)) {
                                assertNotEquals("No letterbox at $w x $h ($x,$y)",blank,bmp.getPixel(x,y))
                            }
                            val layout=a.scene.sceneLayout;val car=a.scene.renderState
                            assertTrue(car.carX-car.width/2>=layout.roadside)
                            assertTrue(car.carX+car.width/2<=layout.width-layout.roadside)
                            assertTrue(car.width*layout.scale<=w*.281f)
                            bmp.recycle()
                        }
                    }
                    a.scene.question="충전비,\n얼마였어요?";a.scene.questionHint="금액 또는 ‘나중에’라고 말해주세요"
                    draw(a.scene,"layers-${w}x$h",w,h).recycle()
                    a.scene.question=""
                }
            }
        }
    }
}
