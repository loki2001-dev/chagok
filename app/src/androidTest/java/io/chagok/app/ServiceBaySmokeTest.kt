package io.chagok.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.chagok.app.companion.*
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import io.chagok.app.ui.MainActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceBaySmokeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun isolate() = resetSmoke(context)

    private fun capture(view: View, name: String?, width: Int, height: Int): Bitmap {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height)
        val image = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        if (name != null) File(context.filesDir,"bay-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        return image
    }

    @Test fun bothSkinsAndOrientationsEnterServiceAndReturnToRoad() {
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val scene = a.scene
                val parked = MotionSnapshot(speedMps=0f,parkGear=true,restricted=false,animationAllowed=true)
                for (skin in CarSkin.entries) for (wide in listOf(false,true)) {
                    scene.skin = skin
                    val name = skin.name + if(wide) "-wide" else "-tall"
                    val w = if(wide) 800 else 320
                    val h = if(wide) 400 else 500
                    for (kind in EnergyKind.entries) {
                        val visit = MonitorState(motion = parked, kind = kind, energyVisitId = "$name-$kind")
                        a.inject(parked)
                        val before = capture(scene,if (kind == EnergyKind.FUEL) "$name-before" else null,w,h)
                        a.inject(visit)
                        repeat(36) { scene.advance(.05f) }
                        assertEquals(1f,scene.fuelVisit.position,0f)
                        val station = capture(scene,if (kind == EnergyKind.FUEL) "$name-fuel" else "$name-charge",w,h)
                        assertTrue(scene.contentDescription.contains(if (kind == EnergyKind.SOC) "충전소" else "주유소"))
                        // A strip of the original streetscape stays pixel-identical beside the station.
                        for (y in 8 until h step 17) assertEquals(before.getPixel(5,y), station.getPixel(5,y))
                        a.inject(parked.copy(parkGear=false,speedMps=12f))
                        repeat(28) { scene.advance(.05f) }
                        assertEquals(0f,scene.fuelVisit.position,0f)
                        val road = capture(scene,if (kind == EnergyKind.FUEL) "$name-road" else null,w,h)
                        assertFalse(station.sameAs(road))
                        before.recycle(); station.recycle(); road.recycle()
                        a.inject(visit)
                        scene.advance(.1f)
                        assertEquals(0f,scene.fuelVisit.position,0f)
                    }
                    for (job in BayJob.entries) {
                        var elapsed = 0L
                        val view = WorkshopView(a,job,skin,setOf(Wheel.FRONT_RIGHT),clock={ elapsed }) {}
                        val street = capture(view,null,w,h)
                        elapsed = (WorkshopView.ENTRY*1000).toLong()
                        val mounted = capture(view,null,w,h)
                        assertFalse(street.sameAs(mounted))
                        elapsed += if (job == BayJob.TIRE) 1180L else 1050L
                        val working = capture(view,if(skin==CarSkin.HYUNDAI) "$name-${job.name}-working" else null,w,h)
                        assertFalse(mounted.sameAs(working))
                        if (job == BayJob.TIRE) {
                            elapsed = (WorkshopView.ENTRY*1000).toLong()+2399L
                            val replaced = capture(view,null,w,h)
                            assertTrue(mounted.sameAs(replaced))
                            replaced.recycle()
                        }
                        elapsed = 7450L
                        val leaving = capture(view,null,w,h)
                        assertFalse(leaving.sameAs(working))
                        street.recycle(); mounted.recycle(); working.recycle(); leaving.recycle()
                    }
                }
                a.inject(MonitorState(motion=parked.copy(animationAllowed=false),energyVisitId="restricted"))
                scene.advance(.5f)
                assertEquals(0f,scene.fuelVisit.position,0f)
            }
        }
    }

    @Test fun repairCutoutsAndStages() {
        val jobs=listOf(BayJob.DRIVE_BELT,BayJob.CABIN_FILTER,BayJob.IGNITION,BayJob.BATTERY_REPLACE,
            BayJob.BRAKES,BayJob.BATTERY)
        ActivityScenario.launch(CompanionHarnessActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                for(name in listOf("transmission","brake-fluid","coil","belt","filter","battery","plug")) {
                    val bitmap=a.assets.open("companion/workshop-$name.webp").use { android.graphics.BitmapFactory.decodeStream(it) }
                    for(x in listOf(0,bitmap.width-1)) for(y in listOf(0,bitmap.height-1))
                        assertEquals(name,0,android.graphics.Color.alpha(bitmap.getPixel(x,y)))
                    bitmap.recycle()
                }
                for(job in jobs) {
                    var ms=0L
                    val v=WorkshopView(a,job,CarSkin.RACER,emptySet(),clock={ms}) {}
                    capture(v,null,800,400).recycle()
                    val frames=listOf(350L,1350L,2500L,3650L).mapIndexed { i,offset ->
                        ms=(WorkshopView.ENTRY*1000).toLong()+offset
                        capture(v,if(job==BayJob.DRIVE_BELT) "detail-belt-$i" else null,800,400)
                    }
                    frames.zipWithNext().forEach { (old,new) -> assertFalse(job.name,old.sameAs(new)) }
                    frames.forEach { it.recycle() }
                }
            }
        }
    }

    /** Optional emulator presentation that cycles through the artwork without creating diary entries. */
    @Test fun maintenanceMotionPresentation() {
        val args=androidx.test.platform.app.InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("workshopDemo")=="true")
        val jobs = listOf(BayJob.OIL,BayJob.DRIVE_BELT,BayJob.TRANSMISSION_FLUID,BayJob.BRAKE_FLUID,
            BayJob.IGNITION,BayJob.CABIN_FILTER,BayJob.TIRE,BayJob.BATTERY_REPLACE,BayJob.WASHER)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (job in jobs) {
                scenario.onActivity { it.showWorkshop(job,setOf(Wheel.FRONT_LEFT),"시연 오일",4.2) }
                onView(withContentDescription(job.label+" 기록 저장 완료"))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
                Thread.sleep(10000)
            }
        }
    }

    private fun tap(text: String) {
        val view = onView(withText(text))
        runCatching { view.perform(scrollTo()) }
        view.perform(click())
    }

    @Test fun dashboardMaintenanceActionsSaveAndPlayTheirSelectedJob() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val store = SqliteStore(context)
            try {
                val diary = DiaryService(store)
                val items = listOf(Item.ENGINE_OIL, Item.DRIVE_BELT, Item.TRANSMISSION_OIL, Item.BRAKE_FLUID,
                    Item.SPARK_PLUG, Item.IGNITION_COIL, Item.CABIN_FILTER, Item.TIRE_REPLACE, Item.BATTERY_12V_REPLACE)
                val shown = java.util.concurrent.ConcurrentLinkedQueue<String>()
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    root.viewTreeObserver.addOnGlobalLayoutListener {
                        val views = arrayListOf<View>()
                        root.findViewsWithText(views, "기록 저장 완료", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                        views.filter { it.isShown }.forEach { shown.add(it.contentDescription.toString()) }
                    }
                }
                for (item in items) {
                    onView(withContentDescription("소모품 관리 탭")).perform(click())
                    onView(withContentDescription(item.label + " 기록 추가")).perform(scrollTo(), click())
                    if (item == Item.ENGINE_OIL) {
                        onView(withHint("제조사")).perform(scrollTo(), replaceText("Chagok Oil"), closeSoftKeyboard())
                        onView(withHint("사용량 L")).perform(scrollTo(), replaceText("4.2"), closeSoftKeyboard())
                    }
                    if (item == Item.TIRE_REPLACE) tap(Wheel.REAR_RIGHT.label)
                    tap("확인하고 저장")
                    val label = item.label + " 기록 저장 완료"
                    assertTrue("저장 후 연출이 표시되지 않음: $label", label in shown)
                    // Espresso can return after the auto-dismissing receipt has already played out.
                    scenario.onActivity { activity ->
                        val views = arrayListOf<View>()
                        activity.window.decorView.findViewsWithText(views, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                        views.firstOrNull { it.isShown }?.performClick()
                    }
                    val saved = diary.confirmedEntries().single { it.item == item }
                    if (item == Item.ENGINE_OIL) assertEquals(EngineOilDetails("Chagok Oil", 4.2), saved.engineOil)
                    if (item == Item.TIRE_REPLACE) assertEquals(setOf(Wheel.REAR_RIGHT), saved.wheels)
                }
                assertEquals(items.size, diary.confirmedEntries().size)
            } finally { store.close() }
        }
    }

    @Test fun saveTireAndWiperThroughParkedDiary() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val shown = java.util.concurrent.ConcurrentLinkedQueue<String>()
            it.onActivity { a ->
                val root = a.window.decorView
                root.viewTreeObserver.addOnGlobalLayoutListener {
                    for (job in listOf("타이어 교체", "와이퍼 점검", "엔진오일 교환")) {
                        val label = "$job 기록 저장 완료"
                        val found = arrayListOf<View>()
                        root.findViewsWithText(found,label,View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                        if (found.any { view -> view.isShown }) shown.add(label)
                    }
                }
            }
            onView(withContentDescription("더보기")).perform(click())
            tap("기록 추가")
            for(item in listOf(Item.ENGINE_OIL,Item.TIRE_REPLACE,Item.WIPER_CHECK)) {
                tap("항목 골라 입력")
                androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.equalTo(item.label)).perform(click())
                if(item==Item.TIRE_REPLACE) tap(Wheel.FRONT_LEFT.label)
                tap("확인하고 저장")
                assertTrue("Expected visible workshop for "+item.label, shown.contains(item.label+" 기록 저장 완료"))
                val completed = android.os.SystemClock.uptimeMillis()+10000
                while (android.os.SystemClock.uptimeMillis()<completed &&
                    runCatching { onView(withContentDescription(item.label+" 기록 저장 완료"))
                        .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed())) }.isSuccess) {
                    Thread.sleep(150)
                }
                onView(withContentDescription(item.label+" 기록 저장 완료"))
                    .check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
                val store = SqliteStore(context)
                try {
                    val record=DiaryService(store).confirmedEntries().single { it.item==item }
                    if(item==Item.TIRE_REPLACE) assertEquals(setOf(Wheel.FRONT_LEFT),record.wheels)
                } finally { store.close() }
            }
        }
    }
}
