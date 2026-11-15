package io.chagok.app.companion

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.util.Log
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import io.chagok.app.drive.DrivingState
import io.chagok.app.ui.MainActivity
import io.chagok.app.vehicle.CompanionTelemetry
import io.chagok.app.vehicle.PleosVehicle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.YearMonth

/** Explicit emulator presentation. Real speed/turn SDK; fuel and STT results are labelled replay. */
class CompanionPresentationActivity : CompanionActivity() {
    override val connectVehicle = false
    override val automaticQuestions = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var db: SqliteStore
    private lateinit var diary: DiaryService
    private lateinit var telemetry: CompanionTelemetry
    private lateinit var vehicle: PleosVehicle
    private lateinit var restrictions: DrivingState
    private lateinit var trips: TripRecorder
    private var odometer: Reading = Reading.Unavailable("연결 중")
    private val rainy get() = intent.getBooleanExtra("landscapeRain", false)
    private val demoSkin get() = if (rainy) CarSkin.HYUNDAI else CarSkin.RACER
    private var energyVisitId: String? = null
    private var phase = "READY"
    private var fuel = 30.0
    private var lastSample = -1000L
    private var lastOdometer = -2000L
    private var savedAt = 0L
    private var summaryAt = 0L
    private var maxBoost = 0f
    private var maxSpeed = 0f
    private var error = ""
    private val events = JSONArray()
    private val controls = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { handleCommand(intent.getStringExtra("presentationCommand")) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        check(packageName.endsWith(".smoke") && Build.HARDWARE in listOf("ranchu", "goldfish"))
        if (intent.getBooleanExtra("resetPresentation", false)) {
            @Suppress("DEPRECATION")
            check(getSystemService(ActivityManager::class.java).getRunningServices(100).none {
                it.service.className == VehicleMonitorService::class.java.name
            }) { "Force-stop only the smoke app before preparing a presentation" }
            deleteDatabase("chagok.db")
            SqliteStore(this).let { store -> try {
                DiaryService(store).updateProfile("차곡 시연 차량", Powertrain.ICE, null)
                store.saveSetting("user", "시연")
                store.saveSetting("companion.skin/시연", demoSkin.name)
                // This visible presentation is the sole fuel writer until handing back to MainActivity.
                store.saveSetting(RecordingStartup.ENABLED, "false")
            } finally { store.close() } }
        }
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        db = SqliteStore(this); diary = DiaryService(db); diary.selectUser("시연")
        check(db.setting(RecordingStartup.ENABLED) == "false") { "Prepare a fresh isolated presentation first" }
        trips = TripRecorder(db)
        telemetry = CompanionTelemetry(this) { Log.i("ChagokDemo", it) }
        vehicle = PleosVehicle(this) { Log.i("ChagokDemo", it) }
        restrictions = DrivingState(this) { Log.i("ChagokDemo", it) }
        scene.skin = demoSkin
        scene.collect = { true }; scene.onEncounter = {} // Scripted encounter preview never awards a friend.
        registerReceiver(controls, IntentFilter("io.chagok.app.smoke.PRESENTATION_COMMAND"), Context.RECEIVER_EXPORTED)
        event("READY")
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommand(intent.getStringExtra("presentationCommand"))
    }
    private fun handleCommand(command: String?) {
        try {
            when (command) {
                "DRIVE", "LEFT", "RIGHT", "ACCELERATE", "PARK", "RESTART" -> { phase = command; event(command) }
                "REFUEL" -> { phase = command; fuel = 80.0; event(command) }
                "AMOUNT" -> {
                    check(conversation.stage == EnergyConversation.Stage.AMOUNT)
                    receiveAnswer("5만 원"); phase = "CONFIRM"; event("AMOUNT_50000")
                    check(diary.ledger(YearMonth.now()).total == 0L)
                }
                "SAVE" -> {
                    check(conversation.stage == EnergyConversation.Stage.CONFIRM)
                    receiveAnswer("맞아")
                    check(diary.confirmedEntries().single().let { it.item == Item.REFUEL && it.cost == 50000L })
                    savedAt = SystemClock.elapsedRealtime(); phase = "SAVED"; event("SAVED_50000")
                    handler.postDelayed(::showSummaryWhenParked, 2000)
                }
            }
        } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName; phase = "ERROR"; event("ERROR") }
        writeStatus()
    }
    private fun event(name: String) { events.put(JSONObject().put("event", name).put("elapsedMs", SystemClock.elapsedRealtime())) }
    override fun footerMessage(message: String): String {
        val line = when (phase) {
            "READY" -> if (rainy) "현대 세단 · 빗길 시연 대기" else "시연 준비 완료 · 시작 신호를 기다려요"
            "DRIVE" -> "운행 시작 · 차량 신호에 맞춰 달려요"
            "LEFT" -> if (rainy) "왼쪽 깜빡이 · 위쪽 차선으로" else "왼쪽 깜빡이 · 왼쪽 차선으로"
            "RIGHT" -> if (rainy) "오른쪽 깜빡이 · 아래쪽 차선으로" else "오른쪽 깜빡이 · 오른쪽 차선으로"
            "ACCELERATE" -> if (rainy) "빗길 가속 · 85km/h" else "급가속 · 부스터 작동"
            "PARK" -> "주유소에 도착했어요"
            "REFUEL" -> "주유 중 · 연료 30% → 80%"
            "RESTART" -> "다시 출발 · 주유 기록을 확인해요"
            "QUESTION" -> "자동 응답 대기 · ‘5만 원’"
            "CONFIRM" -> "금액 확인 · ‘맞아’ 자동 응답"
            "SAVED" -> "50,000원 저장 완료 · 잠시 후 요약"
            "ERROR" -> "시연 오류 · 실행 로그를 확인해 주세요"
            else -> "시연 완료"
        }
        return "$line\n시연 모드 · 주유·답변 재생 / 주행 중 질문 시험"
    }
    private val tick = object : Runnable {
        override fun run() {
            try {
                val now = SystemClock.elapsedRealtime()
                restrictions.refresh()
                val motion = telemetry.snapshot().copy(restricted = restrictions.restricted, animationAllowed = true)
                if (now-lastOdometer >= 2000) {
                    lastOdometer = now; vehicle.readOdometer { odometer = it }
                }
                if (now-lastSample >= 1000) {
                    lastSample = now
                    val parked = motion.parkGear == true && motion.speedMps?.let { it < .1f } == true
                    if (motion.speedMps != null && motion.parkGear != null) {
                        val candidate = diary.observeEnergy(EnergyKind.FUEL, fuel, parked)
                        if (candidate?.collecting == true && !candidate.gapLong) energyVisitId = candidate.id
                        if (!parked) energyVisitId = null
                        trips.sample(diary.profile().id, "시연", Instant.now(), motion.speedMps, parked,
                            (odometer as? Reading.Value)?.observation)
                    }
                }
                applyMonitorState(MonitorState(motion, EnergyKind.FUEL, fuel, "시연 연료", odometer,
                    "시연 관측 중", true, energyVisitId = energyVisitId))
                if (phase == "RESTART" && (motion.speedMps ?: 0f) > 1f) {
                    diary.pendingCandidates().firstOrNull { !it.collecting }?.let {
                        check(beginQuestion(it.id, afterDeparture = true)); phase = "QUESTION"; event("QUESTION")
                    }
                }
                maxBoost = maxOf(maxBoost, scene.renderState.boost)
                maxSpeed = maxOf(maxSpeed, (motion.speedMps ?: 0f)*3.6f)
                writeStatus()
            } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName; phase = "ERROR"; writeStatus() }
            handler.postDelayed(this, 200)
        }
    }
    private fun showSummaryWhenParked() {
        restrictions.refresh()
        if (restrictions.restricted || telemetry.snapshot().parkGear != true) {
            if (SystemClock.elapsedRealtime()-savedAt > 8000) { error = "정차 신호 대기 시간 초과"; phase = "ERROR"; writeStatus() }
            else handler.postDelayed(::showSummaryWhenParked, 100)
            return
        }
        summaryAt = SystemClock.elapsedRealtime(); phase = "SUMMARY"; event("SUMMARY")
        // Hand sole ownership back to the normal monitor only after this activity stops sampling.
        handler.removeCallbacks(tick); db.saveSetting(RecordingStartup.ENABLED, "true"); writeStatus()
        startActivity(Intent(this, MainActivity::class.java))
    }
    private fun writeStatus() {
        val motion = telemetry.snapshot()
        val candidates = diary.pendingCandidates()
        val result = JSONObject().put("phase", phase).put("error", error)
            .put("speedKmh", (motion.speedMps ?: -1f)*3.6f).put("turn", motion.turn)
            .put("parked", motion.parkGear).put("restricted", restrictions.restricted)
            .put("fuelVisit", scene.fuelVisit.position.toDouble()).put("fuelReplay", fuel).put("baseline", db.energyBaseline(diary.profile().id, EnergyKind.FUEL)?.value)
            .put("candidateCount", candidates.size).put("collecting", candidates.firstOrNull()?.collecting)
            .put("confirmedCount", diary.confirmedEntries().size).put("monthTotal", diary.ledger(YearMonth.now()).total)
            .put("stage", conversation.stage.name).put("maxBoost", maxBoost).put("maxSpeedKmh", maxSpeed)
            .put("savedAtMs", savedAt).put("summaryAtMs", summaryAt).put("frames", scene.renderState.frames)
            .put("skin", scene.skin.name).put("landscape", scene.renderState.landscape)
            .put("rainConfirmed", motion.rainConfirmed).put("rain", scene.renderState.rain)
            .put("wiperMode", motion.wiperMode).put("carY", scene.renderState.carY)
            .put("events", events)
        val target = File(filesDir, "presentation-status.json")
        val temp = File(filesDir, "presentation-status.tmp")
        temp.writeText(result.toString(2)); check(temp.renameTo(target))
    }
    override fun onStart() { super.onStart(); if (phase != "SUMMARY") { telemetry.start(); vehicle.start(); scene.resume(); handler.post(tick) } }
    override fun onStop() { handler.removeCallbacksAndMessages(null); telemetry.close(); vehicle.stop(); super.onStop() }
    override fun onDestroy() { unregisterReceiver(controls); restrictions.release(); db.close(); super.onDestroy() }
}
