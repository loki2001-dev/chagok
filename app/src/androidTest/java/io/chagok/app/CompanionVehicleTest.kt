package io.chagok.app

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.companion.*
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Real SDK/foreground-service path; controls are injected only by the emulator driver. */
@RunWith(AndroidJUnit4::class)
class CompanionVehicleTest {
    @Volatile private var observed=MonitorState()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    private fun signal(name:String)=instrument.sendStatus(2,Bundle().apply{putString("chagok_signal",name)})
    private fun await(name:String,timeout:Long=25000,check:()->Boolean){
        val end=SystemClock.elapsedRealtime()+timeout
        do{if(check())return;Thread.sleep(200)}while(SystemClock.elapsedRealtime()<end)
        fail("Timed out: $name; last service state=$observed")
    }
    private fun <T> db(block:(SqliteStore,DiaryService)->T):T{val s=SqliteStore(context);return try{block(s,DiaryService(s))}finally{s.close()}}
    private fun snapshot(s:ActivityScenario<CompanionActivity>):MonitorState{var state=MonitorState();s.onActivity{state=it.monitorState};return state}
    @Test fun drivingWindowRemainsVisibleWithTurnSignalOff() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualConsole")=="true")
        resetSmoke(context)
        try {
            ActivityScenario.launch(CompanionActivity::class.java).use{s->
                await("parked visibility"){snapshot(s).motion.speedMps==0f&&!snapshot(s).motion.restricted}
                signal("DRIVE")
                await("driving UI receives live speed"){snapshot(s).motion.speedMps?.let{it>6f}==true&&snapshot(s).motion.restricted}
                Thread.sleep(3000)
                var evidence=JSONObject()
                s.onActivity{a->
                    assertTrue("Companion view remains shown while driving",a.scene.isShown)
                    assertEquals(View.VISIBLE,a.scene.windowVisibility)
                    assertTrue("App window has foreground focus",a.hasWindowFocus())
                    assertFalse(a.scene.motion.animationAllowed)
                    evidence=JSONObject().put("sceneShown",a.scene.isShown).put("windowFocus",a.hasWindowFocus())
                        .put("speedMps",a.monitorState.motion.speedMps).put("restricted",a.monitorState.motion.restricted)
                        .put("animationAllowedByCurrentAppPolicy",a.monitorState.motion.animationAllowed)
                }
                instrument.uiAutomation.takeScreenshot().let{bmp->File(context.filesDir,"companion-driving-window.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}
                File(context.filesDir,"companion-driving-visibility.json").writeText(evidence.toString(2))
            }
        } finally {signal("PARK")}
    }
    @Test fun nativeLauncherReceivesSdkSignalsAndBackgroundMonitorKeepsCandidates() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("virtualConsole")=="true")
        resetSmoke(context);signal("PARK");signal("WIPER_OFF");signal("TURN_OFF")
        db{_,d->d.updateProfile("EV native",Powertrain.EV,null)}
        val evidence=JSONObject()
        ActivityScenario.launch(CompanionActivity::class.java).use{s->
            // Pleos can overlay its blind-spot camera on turn signals. Observe the service even
            // while Android pauses the app window; UI correctness is checked again after Park.
            val observer=MonitorConnection(context){observed=it}
            instrument.runOnMainSync{observer.open()}
            try {
            await("parked native launcher ready"){val m=snapshot(s).motion;m.speedMps==0f&&!m.restricted&&m.animationAllowed}
            signal("BASELINE")
            await("service SOC baseline"){db{store,d->store.energyBaseline(d.profile().id,EnergyKind.SOC)?.value==30.0}}
            signal("DRIVE");signal("TURN_LEFT")
            await("SDK speed and left signal"){val m=observed.motion;m.speedMps?.let{it>6f}==true&&m.turn == -1}
            val moving=observed.motion
            evidence.put("speedMps",moving.speedMps).put("left",moving.turn)
            await("NO_VIDEO suppresses native animation"){val m=observed.motion;m.restricted&&!m.animationAllowed}
            evidence.put("noVideoWhileDriving",true)
            signal("TURN_RIGHT");await("SDK right signal"){observed.motion.turn==1}
            evidence.put("right",observed.motion.turn)
            signal("PARK");signal("TURN_OFF")
            s.moveToState(Lifecycle.State.RESUMED)
            await("parked native animation returns"){val m=snapshot(s).motion;!m.restricted&&m.animationAllowed&&m.speedMps==0f&&m.turn==0}
            signal("WIPER_LOW")
            await("front low wiper from SDK"){val m=snapshot(s).motion;m.wiperMode=="CONTINUOUS_LEVEL_1"&&m.wiperOn==true}
            val started=SystemClock.elapsedRealtime()
            while(SystemClock.elapsedRealtime()-started<50000){assertFalse("No rain before 60 seconds",snapshot(s).motion.rainConfirmed);Thread.sleep(500)}
            await("60-second rain evidence",20000){snapshot(s).motion.rainConfirmed}
            val confirmedAfter=SystemClock.elapsedRealtime()-started
            assertTrue("Must not infer rain early",confirmedAfter>=58000)
            await("low rain intensity"){snapshot(s).motion.rain in .44f.. .46f}
            evidence.put("rainConfirmedAfterMs",confirmedAfter).put("lowIntensity",snapshot(s).motion.rain)
            signal("WIPER_HIGH");await("high wiper and rain intensity"){val m=snapshot(s).motion;m.wiperMode=="CONTINUOUS_LEVEL_3"&&m.rain>.99f}
            evidence.put("highIntensity",snapshot(s).motion.rain)
            signal("WIPER_OFF");await("off wiper"){snapshot(s).motion.wiperMode=="OFF"}
            assertTrue(snapshot(s).motion.rainConfirmed)
            await("rain stops after grace period",40000){val m=snapshot(s).motion;!m.rainConfirmed&&m.rain==0f}
            evidence.put("rainCleared",true)
            s.moveToState(Lifecycle.State.CREATED)
            signal("RISE")
            await("foreground monitor creates candidate with UI stopped",45000){db{_,d->d.pendingCandidates().any{it.before.value==30.0&&it.after.value==80.0}}}
            db{store,d->assertTrue(d.confirmedEntries().isEmpty());assertTrue(store.encounters(d.profile().id,"나").isEmpty())}
            evidence.put("backgroundCandidate",true).put("backgroundEncounterCount",0)
            s.moveToState(Lifecycle.State.RESUMED)
            await("foreground rebind"){snapshot(s).energy==80.0}
            File(context.filesDir,"companion-sdk-integration.json").writeText(evidence.toString(2))
            } finally {
                signal("PARK");signal("TURN_OFF");signal("WIPER_OFF")
                instrument.runOnMainSync{observer.close()}
            }
        }
    }
}
