package io.chagok.app.companion

import android.content.res.Configuration
import android.os.*

/** Emulator-only feasibility probe. This class and its manifest entry never ship in debug/release. */
class CompanionDrivingProbeActivity : CompanionActivity() {
    override val connectVehicle = false
    var sdkState = MonitorState(); private set
    private val clock = Handler(Looper.getMainLooper())
    private lateinit var connection: MonitorConnection
    private var startedAt = 0L
    private var demoScene = -1
    private val showcase get() = intent.getBooleanExtra("showcase", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        check(packageName.endsWith(".smoke") && Build.HARDWARE in listOf("ranchu", "goldfish")) {
            "Driving animation feasibility probe is only available in the isolated emulator app"
        }
        super.onCreate(savedInstanceState)
        // Visual encounters in this probe are deliberately not awarded to a diary.
        scene.collect = { true }; scene.onEncounter = {}
        connection = MonitorConnection(this) { sdkState = it; renderTelemetry() }
        startedAt = SystemClock.elapsedRealtime()
    }

    override fun footerMessage(message: String) = if (scene.preview)
        "정차 미리보기 · 시험용\n실제 일지에는 기록되지 않아요."
    else "SDK ${sdkState.motion.speedMps?.times(3.6f)?.toInt() ?: 0} km/h · 시험용\n주행 연출을 검증하고 있어요."

    private fun renderTelemetry() {
        // Retain the real restricted flag (and disabled menus), override only rendering in this APK.
        applyMonitorState(sdkState.copy(motion = sdkState.motion.copy(animationAllowed = true)))
        val parkedDemo = showcase && !sdkState.motion.restricted && sdkState.motion.speedMps == 0f
        scene.preview = parkedDemo
        if (parkedDemo) {
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000f
            val section = (seconds / 12).toInt()
            scene.skin = if (section % 4 < 2) CarSkin.RACER else CarSkin.HYUNDAI
            scene.previewSpeed = 45f
            scene.previewTurn = when ((seconds / 4).toInt() % 3) { 0 -> 0; 1 -> -1; else -> 1 }
            scene.previewAcceleration = if (seconds % 12 < 2.5f) 3.5f else 0f
            scene.previewRain = if (section % 2 == 1) .8f else 0f
            if (section != demoScene) { demoScene = section; scene.begin(Encounters.all[section % Encounters.all.size], true) }
            scene.question = if (seconds % 12 in 6f..10f) "충전비,\n얼마였어요?" else ""
            scene.questionContext = "말풍선 디자인 미리보기"
            scene.questionHint = "시연 화면 · 실제 질문이나 기록이 아니에요"
        }
    }

    private val tick = object : Runnable {
        override fun run() { renderTelemetry(); clock.postDelayed(this, 200) }
    }
    override fun onStart() { super.onStart(); connection.open(); scene.resume(); clock.post(tick) }
    override fun onStop() { clock.removeCallbacksAndMessages(null); scene.pause(); connection.close(); super.onStop() }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); renderTelemetry(); scene.resume()
    }
}
