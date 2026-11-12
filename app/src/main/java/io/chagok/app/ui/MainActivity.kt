package io.chagok.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.CandidateRules
import io.chagok.app.domain.CandidateState
import io.chagok.app.domain.ConditionState
import io.chagok.app.domain.DiaryEntry
import io.chagok.app.domain.DiaryService
import io.chagok.app.domain.DiaryService.Companion.km
import io.chagok.app.domain.DiaryService.Companion.won
import io.chagok.app.domain.EnergyKind
import io.chagok.app.domain.EntryStatus
import io.chagok.app.domain.Item
import io.chagok.app.domain.ItemKind
import io.chagok.app.domain.Mission
import io.chagok.app.domain.Powertrain
import io.chagok.app.domain.Reading
import io.chagok.app.domain.ScheduleState
import io.chagok.app.domain.ScheduleStatus
import io.chagok.app.domain.Source
import io.chagok.app.domain.Topic
import io.chagok.app.domain.Wheel
import io.chagok.app.drive.DrivingState
import io.chagok.app.speech.ListenState
import io.chagok.app.speech.SpeechCallbacks
import io.chagok.app.speech.SpeechInput
import io.chagok.app.vehicle.PleosVehicle
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 차곡 정차 화면. 기획서 4장의 여섯 화면을 왼쪽 탭으로 오간다.
 * 상태가 바뀔 때마다 현재 화면을 다시 그린다 (데이터가 작고 화면이 단순하다).
 */
class MainActivity : Activity() {
    private enum class Screen(val label: String) {
        HOME("오늘의 관리"),
        INPUT("정비 입력"),
        CANDIDATES("자동 기록 후보"),
        DIARY("일지·비용"),
        SCHEDULE("관리 일정"),
        DOGAM("관리 도감"),
    }

    private lateinit var ui: Ui
    private lateinit var store: SqliteStore
    private lateinit var service: DiaryService
    private lateinit var vehicle: PleosVehicle
    private lateinit var speech: SpeechInput
    private lateinit var driving: DrivingState

    private lateinit var nav: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var overlay: View

    private var screen = Screen.HOME
    private var user = "나"
    private var odometer: Reading = Reading.Unavailable("아직 읽지 않음")
    private var energyStatus = "확인 중"
    private var energyValue: Double? = null
    private var energyKind: EnergyKind? = null
    private val subscribed = mutableSetOf<EnergyKind>()
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 리스너는 값이 바뀔 때만 오므로, 정차 중에는 마지막 수신값을 주기적으로 다시 넣어 '값 안정'을 판정한다.
     * 화면이 떠 있는 동안만 돈다. 백그라운드 수집은 하지 않는다 (기획서 5장 '알림').
     */
    private val energyTick = object : Runnable {
        override fun run() {
            val kind = energyKind
            val v = energyValue
            if (kind != null && v != null) feedEnergy(kind, v)
            ticker.postDelayed(this, ENERGY_TICK_MS)
        }
    }
    private var listenState = ListenState.IDLE
    private var partial = ""
    private var activeDraftId: String? = null
    private var checks: List<String> = emptyList()
    private var message: String? = null
    private var month: YearMonth = YearMonth.now()
    private var mission: Mission? = null
    private var missionHint: String? = null
    private var restricted = false

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = SqliteStore(this)
        service = DiaryService(store)
        user = store.setting("user") ?: "나"
        service.registerUser(user)
        vehicle = PleosVehicle(this, ::log)
        speech = SpeechInput(this, ::log)
        driving = DrivingState(this, ::log)
        buildShell()
        driving.observe { r ->
            restricted = r && driving.available
            overlay.visibility = if (restricted) View.VISIBLE else View.GONE
            if (restricted && listenState != ListenState.IDLE) speech.cancel()
            render()
        }
        if (vehicle.start()) {
            subscribeEnergyForProfile()
        } else {
            energyStatus = "차량 서비스 미연결"
        }
    }

    private fun subscribeEnergyForProfile() {
        val kind = service.energyKinds().first()
        if (energyKind != kind) {
            energyKind = kind
            energyValue = null
        }
        if (subscribed.add(kind)) subscribeEnergy(kind)
    }

    override fun onResume() {
        super.onResume()
        // 정차 후 앱에 들어오면 새 누적 주행거리를 읽는다. 앱이 꺼져 있던 동안의 이력은 소급하지 않는다
        refreshOdometer()
        ticker.postDelayed(energyTick, ENERGY_TICK_MS)
    }

    override fun onPause() {
        ticker.removeCallbacks(energyTick)
        super.onPause()
    }

    override fun onDestroy() {
        speech.release()
        vehicle.stop()
        driving.release()
        super.onDestroy()
    }

    private fun log(s: String) = Log.i("Chagok", s)

    // ------------------------------------------------------------------ shell

    private fun buildShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.bg) }
        val body = ui.row(0)
        nav = ui.column(8).apply { setPadding(ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(24)) }
        body.addView(ScrollView(this).apply { addView(nav) }, LinearLayout.LayoutParams(ui.dp(240), MATCH_PARENT))
        content = ui.column(16).apply { setPadding(ui.dp(24), ui.dp(24), ui.dp(32), ui.dp(40)) }
        body.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        root.addView(body)
        overlay = ui.column(16).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Palette.bg)
            isClickable = true
            addView(ui.title("주행 중에는 일지를 열 수 없어요"))
            addView(ui.text("정차한 뒤 이용해 주세요.", color = Palette.sub))
            visibility = View.GONE
        }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
    }

    private fun go(s: Screen) {
        screen = s
        message = null
        render()
    }

    private fun render() {
        nav.removeAllViews()
        nav.addView(ui.text("차곡", 30f, Palette.accent, bold = true))
        nav.addView(ui.label("${service.profile().name} · $user"))
        Screen.entries.forEachIndexed { i, s ->
            nav.addView(
                ui.text("${"%02d".format(i + 1)}  ${s.label}", 20f, if (s == screen) Palette.onAccent else Palette.text, bold = s == screen).apply {
                    setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
                    background = ui.round(if (s == screen) Palette.accent else Palette.surface, 12)
                    setOnClickListener { go(s) }
                },
            )
        }
        content.removeAllViews()
        content.addView(ui.title(screen.label))
        message?.let { content.addView(ui.card(ui.text(it, color = Palette.warn), tint = Palette.surface2)) }
        when (screen) {
            Screen.HOME -> home()
            Screen.INPUT -> input()
            Screen.CANDIDATES -> candidates()
            Screen.DIARY -> diary()
            Screen.SCHEDULE -> schedule()
            Screen.DOGAM -> dogam()
        }
    }

    // ------------------------------------------------------------------ vehicle

    private fun refreshOdometer(then: ((Reading) -> Unit)? = null) {
        vehicle.readOdometer { r ->
            odometer = r
            then?.invoke(r)
            render()
        }
    }

    private fun subscribeEnergy(kind: EnergyKind) {
        vehicle.subscribeEnergy(
            kind,
            onValue = { v ->
                if (kind != energyKind) return@subscribeEnergy
                energyValue = v
                feedEnergy(kind, v)
                render()
            },
            onStatus = { s ->
                energyStatus = s
                render()
            },
        )
    }

    private fun feedEnergy(kind: EnergyKind, v: Double?) {
        val c = service.observeEnergy(kind, v, parked = !restricted) ?: return
        message = "새 기록 후보: ${CandidateRules.describe(c)}"
        render()
    }

    private fun odometerText(): String = when (val r = odometer) {
        is Reading.Value -> "${km(r.observation.value)} · ${timeFmt.format(r.observation.receivedAt)} 수신"
        is Reading.Unavailable -> "미수신 (${r.reason})"
    }

    private fun currentKm() = service.currentKm(odometer)

    // ------------------------------------------------------------------ 01 오늘의 관리

    private fun home() {
        content.addView(
            ui.card(
                ui.label("현재 누적 주행거리"),
                ui.big((odometer as? Reading.Value)?.let { km(it.observation.value) } ?: "미수신"),
                ui.label(odometerText()),
                ui.row().apply { addView(ui.button("다시 읽기") { refreshOdometer() }) },
            ),
        )

        val brief = service.briefing(currentKm())
        val next = brief.firstOrNull() ?: service.schedules(currentKm()).firstOrNull { it.state == ScheduleState.UPCOMING }
        content.addView(
            ui.card(
                ui.label("다음 관리"),
                if (next == null) {
                    ui.text("관리 일정에서 주기를 설정하면 여기에 다음 관리가 보여요.", color = Palette.sub)
                } else {
                    ui.text("${next.plan.item.label} · ${next.state.label}", 26f, stateColor(next.state), bold = true)
                },
                *(next?.let { arrayOf(ui.text(conditionLine(it), 20f, Palette.sub)) } ?: emptyArray()),
                *(if (brief.size > 1) arrayOf(ui.label("그 밖에 ${brief.size - 1}건이 임박·도래했어요.")) else emptyArray()),
            ),
        )

        val drafts = service.drafts().size
        val pending = service.pendingCandidates().size
        val changes = service.changesSince(user)
        content.addView(
            ui.card(
                ui.label("확인할 기록"),
                ui.text("저장하지 않은 초안 ${drafts}건 · 자동 기록 후보 ${pending}건"),
                *(if (changes.isNotEmpty()) {
                    arrayOf(
                        ui.text("다른 사람이 새로 기록한 관리 ${changes.size}건", color = Palette.warn),
                        *changes.take(3).map { ui.label("${it.performedOn.format(dateFmt)} · ${it.item.label} · ${it.author}") }.toTypedArray(),
                        ui.row().apply { addView(ui.button("확인했어요") { service.markSeen(user); render() }) },
                    )
                } else {
                    emptyArray()
                }),
                ui.row().apply {
                    if (pending > 0) addView(ui.button("후보 보기") { go(Screen.CANDIDATES) })
                    if (drafts > 0) addView(ui.button("초안 보기") { activeDraftId = service.drafts().first().id; go(Screen.INPUT) })
                },
            ),
        )

        content.addView(
            ui.row().apply {
                addView(ui.button("정비 말하기", primary = true) { go(Screen.INPUT); startListening() })
                addView(ui.button("차량 설정") { vehicleSettings() })
                addView(ui.button("사용자 전환") { switchUser() })
            },
        )
    }

    private fun vehicleSettings() {
        val p = service.profile()
        val name = ui.input("차 이름", p.name, numeric = false)
        val model = ui.input("차종 (선택)", p.model, numeric = false)
        var power = p.powertrain
        val powerRow = ui.row()
        fun drawPower() {
            powerRow.removeAllViews()
            Powertrain.entries.forEach { pt -> powerRow.addView(ui.button(pt.label, primary = pt == power) { power = pt; drawPower() }) }
        }
        drawPower()
        dialog("차량 설정", ui.column().apply { addView(name); addView(model); addView(powerRow); addView(ui.label("동력원에 맞지 않는 항목은 숨겨요. VIN 없이도 일지를 만들 수 있어요.")) }) {
            service.updateProfile(name.text.toString().ifBlank { "우리 차" }, power, model.text.toString().ifBlank { null })
            subscribeEnergyForProfile()
            render()
        }
    }

    private fun switchUser() {
        val name = ui.input("이름 (예: 엄마)", "", numeric = false)
        dialog("공용 일지 사용자", ui.column().apply { addView(ui.label("현재: $user")); addView(name) }) {
            val n = name.text.toString().trim()
            if (n.isNotEmpty()) {
                user = n
                store.saveSetting("user", n)
                service.registerUser(n)
            }
            render()
        }
    }

    // ------------------------------------------------------------------ 02 정비 입력

    private fun ensureMicPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else if (requestCode == 1) { message = "마이크 권한이 없어 음성으로 기록할 수 없어요. 직접 입력해 주세요."; render() }
    }

    private fun startListening() {
        if (restricted) return
        if (!ensureMicPermission()) return
        partial = ""
        speech.listen(object : SpeechCallbacks {
            override fun onState(state: ListenState) { listenState = state; render() }
            override fun onPartial(text: String) { partial = text; render() }
            override fun onFinal(text: String) { partial = text; interpret(text) }
            override fun onFailed(reason: String) { message = "$reason 저장하지 않았어요."; render() }
        })
    }

    /** 최종 텍스트 → 지금 km를 읽고 → 초안. 차량 값을 먼저 받아야 오늘 정비에 붙일 수 있다 */
    private fun interpret(text: String) {
        refreshOdometer { reading ->
            when (val out = service.handleVoice(text, reading, user, activeDraftId)) {
                is DiaryService.VoiceOutcome.Drafted -> { activeDraftId = out.draft.id; checks = out.checks; message = null }
                is DiaryService.VoiceOutcome.Corrected -> message = "비용을 ${won(out.previousCost)}에서 ${won(out.draft.cost)}으로 고쳤어요."
                is DiaryService.VoiceOutcome.Cancelled -> { activeDraftId = null; checks = emptyList(); message = "초안을 취소했어요. 비용·일정에 반영하지 않아요." }
                is DiaryService.VoiceOutcome.Next -> message = out.statuses.firstOrNull()?.let { "${it.plan.item.label}: ${conditionLine(it)}" }
                    ?: "설정된 관리 일정이 없어요."
                is DiaryService.VoiceOutcome.Ask -> message = out.message
            }
            render()
        }
    }

    private fun input() {
        val listening = listenState != ListenState.IDLE
        content.addView(
            ui.card(
                ui.row().apply {
                    addView(ui.chip(listenState.label, if (listening) Palette.accent else Palette.sub))
                    addView(ui.chip(speech.backend))
                },
                ui.text(partial.ifEmpty { "예: \"${examples().first()}\"" }, 26f, if (partial.isEmpty()) Palette.sub else Palette.text),
                ui.row().apply {
                    if (!listening) addView(ui.button("말하기 시작", primary = true, enabled = speech.isAvailable()) { startListening() })
                    else {
                        addView(ui.button("말하기 끝", primary = true) { speech.stop() })
                        addView(ui.button("취소") { speech.cancel(); partial = ""; render() })
                    }
                },
            ),
        )

        val typed = ui.input("정차 중 직접 입력: 인식 텍스트와 같은 방식으로 해석해요", null, numeric = false)
        content.addView(
            ui.card(
                ui.label("직접 입력 (보조 경로)"),
                typed,
                ui.row().apply {
                    addView(ui.button("해석") { typed.text.toString().takeIf { it.isNotBlank() }?.let { partial = it; interpret(it) } })
                    addView(ui.button("항목 골라 입력") { pickItem { item -> manualDraft(item) } })
                },
                ui.label("예시 발화 (시연용)"),
                *examples().chunked(2).map { pair ->
                    ui.row(8).apply { pair.forEach { ex -> addView(ui.chip(ex).apply { setOnClickListener { partial = ex; interpret(ex) } }) } }
                }.toTypedArray(),
            ),
        )

        val draft = activeDraftId?.let(service::entry)?.takeIf { it.status == EntryStatus.DRAFT }
        if (draft == null) {
            activeDraftId = null
            return
        }
        draftCard(draft)
    }

    /** 시연용 예시 발화. 동력원에 맞는 것만 보여 준다 */
    private fun examples(): List<String> = when (service.profile().powertrain) {
        Powertrain.EV -> listOf("타이어 위치 교환했어. 3만 원", "앞 타이어 두 개 교체했어", "어제 와이퍼 점검했어", "충전했어. 1만 2천 원")
        else -> listOf("오늘 엔진오일 갈았어. 8만 원", "타이어 위치 교환했어", "지난주에 오일 갈았어", "주유했어. 5만 원")
    }

    private fun manualDraft(item: Item) {
        val d = service.manualDraft(item, service.today(), null, odometer, user)
        activeDraftId = d.id
        checks = listOf(
            "직접 입력한 초안이에요. 날짜·주행거리·비용을 확인해 주세요.",
            if (d.odometer != null) "주행거리는 차량에서 받은 값이에요." else "주행거리를 받지 못했어요. 직접 입력하거나 비워 두세요.",
        )
        render()
    }

    private fun draftCard(d: DiaryEntry) {
        val kmInput = ui.input("주행거리 km (비우면 미입력)", d.odometer?.value?.let { "%.0f".format(it) }, numeric = true)
        val costInput = ui.input("비용 원 (비우면 미입력)", d.cost?.toString(), numeric = true)
        val wheels = d.wheels.toMutableSet()

        val card = ui.card(
            ui.label("초안 · 확인 후 저장"),
            ui.row().apply {
                addView(ui.text(d.item.label, 28f, bold = true))
                addView(ui.button("항목 변경") { pickItem { item -> replaceItem(d, item) } })
            },
            ui.row().apply {
                addView(ui.text("실제 수행일 ${d.performedOn.format(dateFmt)}", 22f))
                addView(ui.button("날짜") { pickDate(d.performedOn) { service.setDate(d.id, it); render() } })
                addView(ui.button("오늘") { service.setDate(d.id, service.today()); render() })
            },
            ui.label(
                when (d.odometer?.source) {
                    Source.VEHICLE -> "주행거리 출처: 차량 수신 ${d.odometer.receivedAt?.let(timeFmt::format) ?: ""}"
                    Source.USER -> "주행거리 출처: 사용자 입력" + (d.odometer.observed?.let { " (차량 관측 ${km(it.value)} 보존)" } ?: "")
                    else -> "주행거리 미입력 · 거리 조건은 계산을 미뤄요"
                },
            ),
            kmInput,
            costInput,
        )
        if (d.item == Item.TIRE_REPLACE) {
            val row = ui.row(8)
            fun drawWheels() {
                row.removeAllViews()
                Wheel.entries.forEach { w ->
                    row.addView(ui.button(w.label, primary = w in wheels) { if (!wheels.add(w)) wheels.remove(w); drawWheels() })
                }
            }
            drawWheels()
            card.addView(ui.label("교체한 바퀴"))
            card.addView(row)
        }
        checks.forEach { card.addView(ui.text("· $it", 19f, Palette.warn)) }
        d.spokenText?.let { card.addView(ui.label("인식 텍스트: \"$it\" (원음은 저장하지 않아요)")) }
        card.addView(
            ui.row().apply {
                addView(ui.button("확인하고 저장", primary = true) { saveDraft(d, kmInput, costInput, wheels, allowDuplicate = false) })
                addView(ui.button("초안 버리기") { service.cancel(d.id); activeDraftId = null; checks = emptyList(); message = "초안을 버렸어요."; render() })
            },
        )
        content.addView(card)
    }

    private fun replaceItem(d: DiaryEntry, item: Item) {
        service.cancel(d.id)
        val n = service.manualDraft(item, d.performedOn, d.cost, Reading.Unavailable("항목 변경"), user)
        // 차량 근거는 항목이 바뀌어도 그대로 옮긴다
        d.odometer?.let { ev -> store.saveEntry(n.copy(odometer = ev, spokenText = d.spokenText)) }
        activeDraftId = n.id
        render()
    }

    private fun saveDraft(d: DiaryEntry, kmInput: EditText, costInput: EditText, wheels: Set<Wheel>, allowDuplicate: Boolean) {
        val kmValue = kmInput.text.toString().toDoubleOrNull()
        if (kmValue != d.odometer?.value) service.setOdometer(d.id, kmValue)
        val cost = costInput.text.toString().toLongOrNull()
        if (cost != d.cost) service.setCost(d.id, cost)
        if (wheels != d.wheels) service.setWheels(d.id, wheels)
        when (val r = service.confirm(d.id, allowDuplicate)) {
            is DiaryService.ConfirmResult.Saved -> {
                activeDraftId = null
                checks = emptyList()
                partial = ""
                val plan = service.schedules(currentKm()).firstOrNull { it.plan.item == r.entry.item }
                message = buildString {
                    append("${r.entry.item.label} 기록을 저장했어요.")
                    plan?.let { append(" 다음 관리: ${conditionLine(it)}") }
                    r.warnings.forEach { append("\n$it") }
                    if (service.recommendedMission(user, currentKm()) != null) append("\n관리 도감에서 관련 내용을 하나 배워 볼 수 있어요.")
                }
            }
            is DiaryService.ConfirmResult.PossibleDuplicate -> {
                AlertDialog.Builder(this)
                    .setTitle("같은 기록이 있어요")
                    .setMessage("${r.existing.performedOn.format(dateFmt)} ${r.existing.item.label} 기록이 이미 있어요. 그래도 저장할까요?")
                    .setPositiveButton("저장") { _, _ -> saveDraft(service.entry(d.id)!!, kmInput, costInput, wheels, allowDuplicate = true) }
                    .setNegativeButton("돌아가기", null)
                    .show()
                return
            }
            is DiaryService.ConfirmResult.Rejected -> message = r.reason
        }
        render()
    }

    // ------------------------------------------------------------------ 03 자동 기록 후보

    private fun candidates() {
        val kind = service.energyKinds().first()
        content.addView(
            ui.card(
                ui.label("${kind.label} · $energyStatus"),
                ui.big(energyValue?.let { "%.0f%%".format(it) } ?: "미수신"),
                ui.label("정차 중 값이 안정되면 이전 관측과 비교해요. 잔량 상승은 후보일 뿐이며, ${if (kind == EnergyKind.FUEL) "주유량(L)" else "충전량(kWh)"}·금액·장소를 %에서 만들지 않아요."),
            ),
        )
        val pending = service.pendingCandidates()
        if (pending.isEmpty()) content.addView(ui.card(ui.text("확인할 후보가 없어요.", color = Palette.sub)))
        pending.forEach { c ->
            val what = if (c.kind == EnergyKind.FUEL) "주유" else "충전"
            content.addView(
                ui.card(
                    ui.chip("추정 · ${c.state.label}", Palette.warn),
                    ui.text(CandidateRules.describe(c), 24f),
                    ui.label("관측 ${timeFmt.format(c.before.receivedAt)} → ${timeFmt.format(c.after.receivedAt)}"),
                    ui.row().apply {
                        addView(ui.button("${what}으로 기록", primary = true) {
                            refreshOdometer { r ->
                                service.draftFromCandidate(c.id, user, r)?.let {
                                    activeDraftId = it.id
                                    checks = listOf("후보에서 만든 초안이에요. 날짜와 금액을 확인해 주세요. 금액을 비우면 미입력으로 남아요.")
                                }
                                go(Screen.INPUT)
                            }
                        })
                        addView(ui.button("${what} 아님 · 제외") { service.excludeCandidate(c.id); render() })
                    },
                ),
            )
        }
        val decided = store.candidates(service.profile().id).filter { it.state != CandidateState.PENDING }.sortedByDescending { it.after.receivedAt }.take(5)
        if (decided.isNotEmpty()) {
            content.addView(ui.card(ui.label("최근 처리한 후보"), *decided.map { ui.label("${timeFmt.format(it.after.receivedAt)} · ${it.kind.label} +%.0f%%p · ${it.state.label}".format(it.rise)) }.toTypedArray()))
        }
    }

    // ------------------------------------------------------------------ 04 일지·비용

    private fun diary() {
        val ledger = service.ledger(month)
        content.addView(
            ui.card(
                ui.row().apply {
                    addView(ui.button("◀") { month = month.minusMonths(1); render() })
                    addView(ui.text("${month.year}년 ${month.monthValue}월", 26f, bold = true))
                    addView(ui.button("▶", enabled = month < YearMonth.now()) { month = month.plusMonths(1); render() })
                },
                ui.big(won(ledger.total)),
                ui.label("확정 ${ledger.entryCount}건 · 금액 미입력 ${ledger.missingCostCount}건 (0원으로 합산하지 않음)"),
                *ledger.byItem.entries.sortedByDescending { it.value }.map { ui.text("${it.key.label}  ${won(it.value)}", 20f, Palette.sub) }.toTypedArray(),
            ),
        )
        val timeline = service.timeline()
        if (timeline.isEmpty()) content.addView(ui.card(ui.text("아직 확정한 기록이 없어요.", color = Palette.sub)))
        timeline.forEach { e ->
            val cancelled = e.status == EntryStatus.CANCELLED
            content.addView(
                ui.card(
                    ui.row().apply {
                        addView(ui.text("${e.performedOn.format(dateFmt)}  ${e.item.label}", 24f, if (cancelled) Palette.sub else Palette.text, bold = true))
                        if (cancelled) addView(ui.chip("취소", Palette.danger))
                        if (e.version > 1) addView(ui.chip("수정 ${e.version - 1}회"))
                    },
                    ui.label(
                        listOfNotNull(
                            e.odometer?.let { "${km(it.value)}(${it.source.label})" } ?: "주행거리 미입력",
                            won(e.cost),
                            e.wheels.takeIf { it.isNotEmpty() }?.joinToString { it.label },
                            "기록 ${e.author}",
                            e.candidateId?.let { "후보에서 확정" },
                        ).joinToString(" · "),
                    ),
                    *(e.memo?.let { arrayOf(ui.text(it, 19f, Palette.sub)) } ?: emptyArray()),
                ).apply { if (!cancelled) setOnClickListener { entryActions(e) } },
            )
        }
        content.addView(ui.row().apply { addView(ui.button("정비 리포트 내보내기") { exportReport() }) })
    }

    private fun entryActions(e: DiaryEntry) {
        val cost = ui.input("비용 원 (비우면 미입력)", e.cost?.toString(), numeric = true)
        val history = e.history.joinToString("\n") { "v${it.version} ${timeFmt.format(it.changedAt)} ${it.summary}" }.ifEmpty { "변경 이력 없음" }
        AlertDialog.Builder(this)
            .setTitle("${e.performedOn.format(dateFmt)} ${e.item.label}")
            .setView(ui.column().apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0); addView(cost); addView(ui.text(history, 16f, Palette.sub)) })
            .setPositiveButton("비용 저장") { _, _ -> service.setCost(e.id, cost.text.toString().toLongOrNull()); render() }
            .setNeutralButton("기록 취소") { _, _ ->
                AlertDialog.Builder(this).setMessage("이 기록을 취소할까요? 비용·일정·도장에서 빠지고 이력은 남아요.")
                    .setPositiveButton("취소하기") { _, _ -> service.cancel(e.id); render() }
                    .setNegativeButton("돌아가기", null).show()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun exportReport() {
        val to = service.today()
        val text = service.report(to.minusYears(1), to, null, includeMemos = true)
        val file = File(getExternalFilesDir(null), "chagok-report-$to.txt").apply { writeText(text) }
        AlertDialog.Builder(this)
            .setTitle("최근 1년 정비 리포트")
            .setMessage("$text\n저장 위치: ${file.absolutePath}\nVIN과 개인 프로필은 넣지 않았어요.")
            .setPositiveButton("닫기", null)
            .show()
    }

    // ------------------------------------------------------------------ 05 관리 일정

    private fun schedule() {
        content.addView(
            ui.card(
                ui.text("주기는 차종·연식·동력원·사용 조건에 맞는 매뉴얼이나 정비 안내를 보고 직접 정해요. 앱은 정해진 주기로 날짜와 거리만 계산해요.", 20f, Palette.sub),
                ui.row().apply {
                    addView(ui.button("시연용 예시값 넣기") {
                        val src = "시연용 예시값 (권장 주기 아님)"
                        if (Item.ENGINE_OIL.availableFor(service.profile().powertrain)) service.setPlan(Item.ENGINE_OIL, 10_000, 12, src)
                        service.setPlan(Item.TIRE_ROTATION, 10_000, 12, src)
                        render()
                    })
                },
            ),
        )
        val statuses = service.schedules(currentKm()).associateBy { it.plan.item }
        service.items().filter { it.kind == ItemKind.REPLACE || it.kind == ItemKind.INSPECT }.forEach { item ->
            val s = statuses[item]
            val card = ui.card(
                ui.row().apply {
                    addView(ui.text(item.label, 24f, bold = true))
                    addView(ui.chip(if (item.kind == ItemKind.INSPECT) "점검" else "교환"))
                    s?.let { addView(ui.chip(it.state.label, stateColor(it.state))) }
                },
            )
            if (s == null) {
                card.addView(ui.label("주기 미설정"))
            } else {
                card.addView(ui.text(conditionLine(s), 20f))
                card.addView(ui.label("기준: ${s.basis?.let { "${it.performedOn.format(dateFmt)}${it.odometer?.let { o -> " · ${km(o.value)}" } ?: ""}" } ?: "기록 없음"} · 출처: ${s.plan.source} (${s.plan.setOn.format(dateFmt)} 설정)"))
                if (s.odometerReversed) card.addView(ui.text("현재 주행거리가 기준 기록보다 작아요. 차량 변경 등 확인이 필요해요.", 19f, Palette.danger))
                if (s.plan.snoozedUntil?.let { !service.today().isAfter(it) } == true) card.addView(ui.label("${s.plan.snoozedUntil.format(dateFmt)}까지 미뤘어요"))
            }
            card.addView(
                ui.row().apply {
                    addView(ui.button("주기 설정") { planDialog(item, s) })
                    if (s != null) {
                        addView(ui.button("7일 미루기") { service.snooze(item, service.today().plusDays(7)); render() })
                        addView(ui.button(if (s.plan.notificationsOn) "알림 끄기" else "알림 켜기") { service.setNotifications(item, !s.plan.notificationsOn); render() })
                    }
                },
            )
            content.addView(card)
        }
    }

    private fun planDialog(item: Item, s: ScheduleStatus?) {
        val kmIn = ui.input("거리 주기 km (비우면 사용 안 함)", s?.plan?.intervalKm?.toString(), numeric = true)
        val moIn = ui.input("기간 주기 개월 (비우면 사용 안 함)", s?.plan?.intervalMonths?.toString(), numeric = true)
        val srcIn = ui.input("출처 (예: 차량 매뉴얼)", s?.plan?.source, numeric = false)
        dialog("${item.label} 주기", ui.column().apply { addView(kmIn); addView(moIn); addView(srcIn) }) {
            runCatching { service.setPlan(item, kmIn.text.toString().toIntOrNull(), moIn.text.toString().toIntOrNull(), srcIn.text.toString()) }
                .onFailure { message = it.message }
            render()
        }
    }

    // ------------------------------------------------------------------ 06 관리 도감

    private fun dogam() {
        if (restricted) {
            content.addView(ui.card(ui.text("학습과 도장은 정차 중에만 열려요.", color = Palette.sub)))
            return
        }
        val stamps = service.stamps(user).associateBy { it.topic }
        val grid = ui.row()
        Topic.entries.forEach { t ->
            val p = stamps[t]
            grid.addView(
                ui.weighted(
                    ui.card(
                        ui.text(if (p != null && !p.needsReview) "●" else "○", 40f, if (p != null && !p.needsReview) Palette.accent else Palette.line),
                        ui.text(t.label, 20f, bold = true),
                        ui.label(
                            when {
                                !t.available -> "콘텐츠 준비 중"
                                p == null -> "아직 배우지 않음"
                                p.needsReview -> "근거 기록이 취소되어 다시 확인해요"
                                else -> "${timeFmt.format(p.completedAt)} 완료"
                            },
                        ),
                    ),
                ),
            )
        }
        content.addView(grid)
        val progress = stamps.values.count { !it.needsReview }
        content.addView(ui.label("배운 주제 $progress / ${Topic.entries.count { it.available }} · 도장은 학습 진행이며 차량 상태나 운전 실력 점수가 아니에요."))

        val m = mission?.takeIf { cur -> service.missions(user, currentKm()).any { it.topic == cur.topic } }
            ?: service.recommendedMission(user, currentKm())
        mission = m
        if (m == null) {
            content.addView(ui.card(ui.text("지금 추천할 미션이 없어요. 정비 기록을 확정하고 관리 일정을 설정하면 새 미션이 열려요.", color = Palette.sub)))
            return
        }
        val card = ui.card(
            ui.chip("추천 미션 · ${m.topic.label}", Palette.accent),
            ui.text(m.card, 21f),
            ui.text(m.question, 24f, bold = true),
        )
        m.choices.forEachIndexed { i, c ->
            card.addView(ui.button(c) {
                val r = service.answer(user, m, i)
                if (r.correct) {
                    mission = null
                    missionHint = null
                    message = if (r.stamped) "'${m.topic.label}' 도장을 찍었어요." else "정답이에요. 이미 받은 도장이라 새로 찍지 않아요."
                } else {
                    missionHint = r.hint
                }
                render()
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        }
        missionHint?.let { card.addView(ui.text("힌트: $it 다시 골라 보세요.", 20f, Palette.warn)) }
        card.addView(ui.row().apply { addView(ui.button("건너뛰기") { mission = null; missionHint = null; go(Screen.HOME) }) })
        content.addView(card)
    }

    // ------------------------------------------------------------------ helpers

    private companion object {
        const val ENERGY_TICK_MS = 5_000L
    }

    private fun conditionLine(s: ScheduleStatus): String {
        val kmPart = when (s.kmState) {
            ConditionState.NOT_SET -> null
            ConditionState.PENDING -> "거리: 계산 보류"
            else -> "거리: ${s.nextKm?.let(::km)}" + (s.remainingKm?.let { if (it > 0) " (${km(it)} 남음)" else " (도래)" } ?: "")
        }
        val datePart = when (s.dateState) {
            ConditionState.NOT_SET -> null
            ConditionState.PENDING -> "날짜: 계산 보류"
            else -> "날짜: ${s.nextDate?.format(dateFmt)}" + (s.remainingDays?.let { if (it > 0) " (${it}일 남음)" else " (도래)" } ?: "")
        }
        return listOfNotNull(kmPart, datePart).joinToString(" · ").ifEmpty { "주기 미설정" } +
            if (s.kmState != ConditionState.NOT_SET && s.dateState != ConditionState.NOT_SET) " · 먼저 오는 조건" else ""
    }

    private fun stateColor(s: ScheduleState) = when (s) {
        ScheduleState.DUE -> Palette.danger
        ScheduleState.SOON -> Palette.warn
        ScheduleState.UPCOMING -> Palette.accent
        else -> Palette.sub
    }

    private fun pickItem(onPick: (Item) -> Unit) {
        val items = service.items()
        AlertDialog.Builder(this)
            .setTitle("정비 항목")
            .setItems(items.map { it.label }.toTypedArray()) { _, i -> onPick(items[i]) }
            .show()
    }

    private fun pickDate(initial: LocalDate, onPick: (LocalDate) -> Unit) {
        DatePickerDialog(this, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
            .apply { datePicker.maxDate = System.currentTimeMillis() }
            .show()
    }

    private fun dialog(title: String, body: View, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(FrameLayout(this).apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0); addView(body, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) })
            .setPositiveButton("저장") { _, _ -> onOk() }
            .setNegativeButton("취소", null)
            .show()
    }
}
