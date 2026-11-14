package io.chagok.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Configuration
import android.app.Dialog
import android.widget.CheckBox
import android.widget.Toast
import android.text.TextWatcher
import android.text.Editable
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
import io.chagok.app.companion.MonitorState
import io.chagok.app.companion.RecordingStartup
import io.chagok.app.companion.VehicleMonitorService
import io.chagok.app.companion.MonitorConnection
import io.chagok.app.companion.CompanionActivity

/**
 * 주행 화면에서 필요할 때 여는 정차 중 차량 일지와 관리 화면.
 * 상태가 바뀔 때마다 현재 화면을 다시 그린다 (데이터가 작고 화면이 단순하다).
 */
class MainActivity : Activity() {
    private enum class Screen(val label: String) {
        HOME("차량 요약"),
        DRIVES("운행 기록"),
        RECORDING("자동 기록 설정"),
        INPUT("기록 추가"),
        CANDIDATES("확인할 기록"),
        DIARY("차량 일지"),
        SCHEDULE("소모품 관리"),
        STATS("비용 통계"),
        DETAIL("기록 상세"),
        MORE("더보기"),
        DOGAM("관리 도감"),
    }

    private lateinit var ui: Ui
    private lateinit var store: SqliteStore
    private lateinit var service: DiaryService
    private lateinit var vehicle: PleosVehicle
    private lateinit var speech: SpeechInput
    private lateinit var driving: DrivingState
    private lateinit var monitor: MonitorConnection

    private lateinit var nav: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var overlay: View
    private lateinit var contentScroll: ScrollView
    private var windowWidthDp = 0
    private var selectedEntryId: String? = null
    private var detailReturn = Screen.DIARY
    private var diaryFilter = 0
    private val primaryScreens = listOf(Screen.HOME, Screen.DIARY, Screen.SCHEDULE, Screen.STATS)

    private var screen = Screen.HOME
    private var user = "나"
    private var odometer: Reading = Reading.Unavailable("아직 읽지 않음")
    private var monitorState = MonitorState()
    private var energyStatus = "확인 중"
    private var energyValue: Double? = null
    private var energyKind: EnergyKind? = null
    private var listenState = ListenState.IDLE
    private var partial = ""
    private var activeDraftId: String? = null
    private var checks: List<String> = emptyList()
    private var message: String? = null
    private var month: YearMonth = YearMonth.now()
    private var mission: Mission? = null
    private var missionHint: String? = null
    private var restricted = true
    private var resumed = false
    private var destroyed = false
    private var requestGeneration = 0
    private val editValues = mutableMapOf<String, String>()
    private val dialogs = mutableListOf<Dialog>()
    private var handlingTouch = false
    private var renderQueued = false

    private val dateFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.configure(resources.configuration)
        ui = Ui(this)
        windowWidthDp = resources.configuration.screenWidthDp
        store = SqliteStore(this)
        service = DiaryService(store)
        user = store.setting("user") ?: "나"
        service.selectUser(user)
        savedInstanceState?.let { state ->
            screen = runCatching { Screen.valueOf(state.getString("screen") ?: "HOME") }.getOrDefault(Screen.HOME)
            activeDraftId = state.getString("draft")
            partial = state.getString("partial").orEmpty()
            month = YearMonth.parse(state.getString("month") ?: YearMonth.now().toString())
            selectedEntryId = state.getString("entry")
            diaryFilter = state.getInt("filter")
            detailReturn = runCatching { Screen.valueOf(state.getString("detailReturn") ?: "DIARY") }.getOrDefault(Screen.DIARY)
            state.getBundle("edits")?.let { b -> b.keySet().forEach { editValues[it] = b.getString(it).orEmpty() } }
        }
        vehicle = PleosVehicle(this, ::log)
        speech = SpeechInput(this, ::log) { if (resumed && !destroyed && screen == Screen.INPUT) render() }
        driving = DrivingState(this, ::log)
        monitor = MonitorConnection(this) { state ->
            if (resumed && !destroyed) {
                val nextOdometer = state.odometer as? Reading.Value
                val previousOdometer = odometer as? Reading.Value
                val odometerChanged = nextOdometer != null && (previousOdometer == null ||
                    nextOdometer.observation.receivedAt.isAfter(previousOdometer.observation.receivedAt))
                val monitorChanged=monitorState.recordingStatus!=state.recordingStatus||monitorState.trip?.endedAt!=state.trip?.endedAt
                monitorState=state
                val changed = monitorChanged || odometerChanged || energyValue != state.energy || energyStatus != state.energyStatus || energyKind != state.kind
                if (odometerChanged) odometer = nextOdometer!!
                energyValue = state.energy; energyStatus = state.energyStatus; energyKind = state.kind
                if (changed && screen != Screen.INPUT) render()
            }
        }
        buildShell()
        driving.observe { r ->
            val wasRestricted = restricted
            restricted = r
            overlay.visibility = if (restricted) View.VISIBLE else View.GONE
            if (restricted) {
                requestGeneration++
                dialogs.toList().forEach { it.dismiss() }
                if (listenState != ListenState.IDLE) speech.cancel()
            }
            if (wasRestricted != restricted || !resumed) render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        driving.refresh()
        vehicle.start()
        monitor.open()
        // 정차 후 앱에 들어오면 새 누적 주행거리를 읽는다. 앱이 꺼져 있던 동안의 이력은 소급하지 않는다
        refreshOdometer()
    }

    override fun onPause() {
        resumed = false
        requestGeneration++
        speech.cancel()
        monitor.close()
        vehicle.stop()
        energyValue = null
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        speech.release()
        vehicle.stop()
        driving.release()
        store.close()
        super.onDestroy()
    }

    private fun log(s: String) = Log.i("Chagok", s)

    // ------------------------------------------------------------------ shell

    private fun buildShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.bg) }
        val wide = windowWidthDp >= 1100
        val inset = if (windowWidthDp < 400) 16 else 24
        ui.availableWidthDp = windowWidthDp - (if (wide) 184 else 0) - inset * 2
        val shell = ui.column(0)
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(inset), ui.dp(8), ui.dp(inset), ui.dp(8))
            addView(ui.text("차곡", if (windowWidthDp < 400) 28f else 32f, Palette.accent, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(shortButton("주행", "주행 화면으로 돌아가기") { returnToCompanion() })
            addView(shortButton("더보기", "더보기") { go(Screen.MORE) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ui.dp(8) })
        }
        shell.addView(header)
        val body = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        nav = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            setBackgroundColor(Palette.surface)
        }
        content = ui.column(16).apply { setPadding(ui.dp(inset), ui.dp(16), ui.dp(inset), ui.dp(32)) }
        contentScroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        if (wide) {
            body.addView(nav, LinearLayout.LayoutParams(ui.dp(184), MATCH_PARENT))
            body.addView(contentScroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        } else {
            body.addView(contentScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            body.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        shell.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(shell, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        overlay = ui.column(16).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Palette.bg)
            isClickable = true
            addView(ui.title("정차 상태가 확인되면 이용할 수 있어요"))
            addView(ui.text("주행 중이거나 차량 상태를 확인할 수 없어요.", color = Palette.sub))
            visibility = if (restricted) View.VISIBLE else View.GONE
        }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
    }

    private fun shortButton(label: String, description: String, click: () -> Unit) = ui.text(label, 22f, bold = true).apply {
        gravity = Gravity.CENTER; minHeight = ui.dp(64)
        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        background = ui.touch(Palette.surface, 12)
        isClickable = true; isFocusable = true; contentDescription = description
        setOnClickListener { click() }
    }

    private fun returnToCompanion() {
        startActivity(Intent(this, CompanionActivity::class.java)); finish()
    }

    private fun go(s: Screen) {
        if (restricted) return
        if (screen == Screen.INPUT && s != Screen.INPUT) speech.cancel()
        screen = s
        message = null
        render()
        contentScroll.scrollTo(0, 0)
    }

    private fun render() {
        if (destroyed) return
        // 차량 콜백이 DOWN과 UP 사이 View를 갈아 끼워 탭이 사라지지 않도록 한다.
        if (handlingTouch) { renderQueued = true; return }
        nav.removeAllViews()
        val wide = windowWidthDp >= 1100
        val labels = listOf("요약", "일지", "관리", "통계")
        primaryScreens.forEachIndexed { index, s ->
            val selected = s == screen || (screen == Screen.DETAIL && s == detailReturn)
            val tab = ui.text(labels[index], if (wide) 24f else 22f, if (selected) Palette.accent else Palette.sub, true).apply {
                gravity = Gravity.CENTER; minHeight = ui.dp(if(wide)76 else 92)
                setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12))
                background = ui.touch(if (selected) Palette.accentSoft else Palette.surface, 16)
                val icon = AppIcon(listOf(Icon.HOME,Icon.JOURNAL,Icon.WRENCH,Icon.CHART)[index],if(selected)Palette.accent else Palette.sub).apply { setBounds(0,0,ui.dp(28),ui.dp(28)) }
                if(wide)setCompoundDrawablesRelative(icon,null,null,null) else setCompoundDrawablesRelative(null,icon,null,null)
                compoundDrawablePadding=ui.dp(6)
                isSelected = selected; isFocusable = true; contentDescription = "${s.label} 탭"
                setOnClickListener { go(s) }
            }
            nav.addView(tab, if (wide) LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ui.dp(8) }
                else LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        content.removeAllViews()
        if (screen !in primaryScreens) content.addView(LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(shortButton("‹", "이전 화면") { backToParent() },LinearLayout.LayoutParams(ui.dp(56),ui.dp(64)).apply { marginEnd=ui.dp(12) })
            addView(ui.title(screen.label),LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        }) else content.addView(ui.title(screen.label))
        if (screen == Screen.HOME) content.addView(ui.text("${service.profile().name} · $user", 22f, Palette.sub))
        message?.let { content.addView(ui.card(ui.text(it, color = Palette.warn), tint = Palette.surface2)) }
        when (screen) {
            Screen.HOME -> home()
            Screen.DRIVES -> content.addView(AutoDiaryView(ui,store,service,user).history(month,{ month=month.minusMonths(1);render() },{month=month.plusMonths(1);render()}))
            Screen.RECORDING -> recordingSettings()
            Screen.INPUT -> input()
            Screen.CANDIDATES -> candidates()
            Screen.DIARY -> diary()
            Screen.SCHEDULE -> schedule()
            Screen.DOGAM -> dogam()
            Screen.STATS -> content.addView(dashboard().statistics(month, { month = it; render() }, { month = it; go(Screen.DIARY) }))
            Screen.DETAIL -> entryDetail()
            Screen.MORE -> more()
        }
    }

    private fun dashboard() = DiaryDashboard(this, ui, service)
    private fun openEntry(entry: DiaryEntry) {
        selectedEntryId = entry.id; detailReturn = screen; go(Screen.DETAIL)
    }
    private fun backToParent() = go(if (screen == Screen.DETAIL) detailReturn else Screen.HOME)
    @Deprecated("Activity back callback for the supported platform")
    override fun onBackPressed() {
        if (screen != Screen.HOME && !restricted) backToParent() else returnToCompanion()
    }

    // ------------------------------------------------------------------ vehicle

    private fun refreshOdometer(then: ((Reading) -> Unit)? = null) {
        vehicle.readOdometer { r ->
            if (!resumed || destroyed) return@readOdometer
            odometer = r
            then?.invoke(r)
            if (screen != Screen.INPUT || then != null) render()
        }
    }

    private fun odometerText(): String = when (val r = odometer) {
        is Reading.Value -> "${km(r.observation.value)} · ${timeFmt.format(r.observation.receivedAt)} 수신"
        is Reading.Unavailable -> "미수신 (${r.reason})"
    }

    private fun currentKm() = service.currentKm(odometer)

    // ------------------------------------------------------------------ 01 오늘의 관리

    private fun home() {
        content.addView(AutoDiaryView(ui,store,service,user).today { month=YearMonth.now();go(Screen.DRIVES) })
        content.addView(dashboard().home(currentKm(), { go(Screen.SCHEDULE) }, { go(Screen.CANDIDATES) },
            { go(Screen.DIARY) }, { go(Screen.STATS) }, ::openEntry))
        val changes = service.changesSince(user)
        if (changes.isNotEmpty()) content.addView(ui.card(
            ui.text("함께 쓰는 차량의 새 기록 ${changes.size}건", 24f, bold = true),
            *changes.take(3).map { ui.text("${it.performedOn.format(dateFmt)} · ${it.item.label} · ${it.author}", 22f, Palette.sub) }.toTypedArray(),
            ui.button("확인했어요") { service.markSeen(user); render() }))
    }

    private fun recordingSettings() {
        val enabled=store.setting(RecordingStartup.ENABLED)=="true"
        content.addView(ui.card(ui.text(monitorState.recordingStatus,28f,bold=true),
            ui.text("자동 기록은 차량 안에 저장해요. 꺼 두면 앱을 다시 열어도 기록을 시작하지 않아요.",22f,Palette.sub),
            ui.button(if(enabled)"자동 기록 끄기" else "자동 기록 켜기",primary=!enabled) {
                store.saveSetting(RecordingStartup.ENABLED,(!enabled).toString());monitor.close()
                if(enabled)stopService(Intent(this,VehicleMonitorService::class.java))
                monitor.open();render()
            }))
        val queue=service.questionQueue(user)
        content.addView(ui.card(ui.text("확인은 편할 때",26f,bold=true),
            ui.text("미룬 질문은 다음 정차 때 이어가요. 응답하지 않아도 초안은 남아요.",22f,Palette.sub),
            ui.button(if(queue.muted())"오늘 질문 다시 받기" else "오늘은 묻지 않기") { if(queue.muted())queue.unmute() else queue.muteToday();render() }))
        content.addView(ui.section("기록 연결 이력","받지 못한 구간을 임의로 채우지 않아요"))
        val gaps=store.gaps(service.profile().id).sortedByDescending { it.to }.take(10)
        if(gaps.isEmpty())content.addView(ui.text("저장된 연결 공백이 없어요.",22f,Palette.sub))
        val fmt=DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(ZoneId.systemDefault())
        gaps.forEach { content.addView(ui.card(ui.text("${fmt.format(it.from)} → ${fmt.format(it.to)}",24f,bold=true),ui.label(it.reason))) }
    }

    private fun more() {
        content.addView(ui.text("${service.profile().name} · $user",24f,Palette.sub))
        content.addView(ui.card(
            ui.actionRow("확인할 기록 ${service.reviewCount()}건","자동 감지와 작성 중인 기록",Icon.JOURNAL) { go(Screen.CANDIDATES) },ui.divider(),
            ui.actionRow("기록 추가","지난 정비를 남기거나 말로 입력",Icon.PLUS) { activeDraftId=null;go(Screen.INPUT) }))
        content.addView(ui.section("차량과 사용자"))
        content.addView(ui.card(
            ui.actionRow("차량 설정",service.profile().name,Icon.CAR) { vehicleSettings() },ui.divider(),
            ui.actionRow("사용자 전환",user,Icon.USER) { switchUser() },ui.divider(),
            ui.actionRow("공유 범위","내 기록을 볼 수 있는 사용자",Icon.SHARE) { sharingDialog() }))
        content.addView(ui.card(
            ui.actionRow("오늘의 드라이브","자동으로 모은 거리·시간·만남",Icon.CAR) { month=YearMonth.now();go(Screen.DRIVES) },ui.divider(),
            ui.actionRow("자동 기록 설정",monitorState.recordingStatus,Icon.SETTINGS) { go(Screen.RECORDING) }))
        content.addView(ui.section("기록 활용"))
        content.addView(ui.card(
            ui.actionRow("관리 도감","내 차를 알아가는 짧은 학습",Icon.BOOK) { go(Screen.DOGAM) },ui.divider(),
            ui.actionRow("정비 리포트 내보내기","기간과 항목을 골라 파일로 저장",Icon.EXPORT) { exportReport() },ui.divider(),
            ui.actionRow("저장한 리포트","보관한 파일 열기·공유",Icon.JOURNAL) { savedReports() }))
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
        dialog("차량 설정", ui.column().apply { addView(ui.field("차 이름",name)); addView(ui.field("차종 · 선택",model)); addView(ui.text("동력원",22f,bold=true)); addView(powerRow); addView(ui.label("동력원에 맞지 않는 항목은 숨겨요. VIN 없이도 일지를 만들 수 있어요.")) }) {
            service.updateProfile(name.text.toString().ifBlank { "우리 차" }, power, model.text.toString().ifBlank { null })
            energyValue = null
            render()
        }
    }

    private fun switchUser() {
        val name = ui.input("이름 (예: 엄마)", "", numeric = false)
        dialog("공용 일지 사용자", ui.column().apply { addView(ui.label("현재: $user")); addView(ui.field("사용자 이름",name)) }) {
            val n = name.text.toString().trim()
            if (n.isNotEmpty()) {
                user = n
                store.saveSetting("user", n)
                speech.cancel()
                requestGeneration++
                activeDraftId = null
                editValues.clear()
                mission = null
                missionHint = null
                service.selectUser(n)
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
        if (restricted || !resumed) return
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
        if (restricted || !resumed) return
        val generation = ++requestGeneration
        message = "내용을 확인하고 있어요."
        render()
        refreshOdometer { reading ->
            if (generation != requestGeneration || restricted || !resumed) return@refreshOdometer
            when (val out = service.handleVoice(text, reading, user, activeDraftId)) {
                is DiaryService.VoiceOutcome.Drafted -> { activeDraftId = out.draft.id; checks = out.checks; message = null }
                is DiaryService.VoiceOutcome.Corrected -> {
                    editValues.remove("${out.draft.id}.cost")
                    message = "비용을 ${won(out.previousCost)}에서 ${won(out.draft.cost)}으로 고쳤어요."
                }
                is DiaryService.VoiceOutcome.Cancelled -> { activeDraftId = null; checks = emptyList(); message = "초안을 취소했어요. 비용·일정에 반영하지 않아요." }
                is DiaryService.VoiceOutcome.Next -> message = out.statuses.firstOrNull()?.let { "${it.plan.item.label}: ${conditionLine(it)}" }
                    ?: "설정된 관리 일정이 없어요."
                is DiaryService.VoiceOutcome.Ask -> message = out.message
            }
            render()
        }
    }

    private fun input() {
        val draft=activeDraftId?.let(service::entry)?.takeIf { it.status==EntryStatus.DRAFT }
        if(draft!=null) {
            draftCard(draft)
            content.addView(ui.button(if(listenState==ListenState.IDLE) "말로 수정하기" else "말하기 끝") {
                if(listenState==ListenState.IDLE)startListening() else speech.stop()
            })
            if(listenState!=ListenState.IDLE) content.addView(ui.text("${listenState.label} · $partial",24f,Palette.accent))
            return
        }
        activeDraftId=null
        val listening=listenState!=ListenState.IDLE
        val voice=ui.card(
            ui.withIcon(ui.text("말로 간단하게",28f,bold=true),Icon.MIC),
            ui.text(partial.ifEmpty { "“${examples().first()}”" },28f,if(partial.isEmpty())Palette.sub else Palette.text),
            ui.text(if(listening)listenState.label else if(speech.isAvailable())"차 안에서 음성을 처리해요" else "음성 연결을 확인해 주세요",22f,Palette.sub))
        voice.addView(if(!listening)ui.withIcon(ui.button("말하기 시작",primary=true,enabled=speech.isAvailable()) { startListening() },Icon.MIC)
            else ui.button("말하기 끝",primary=true) { speech.stop() })
        if(listening)voice.addView(ui.button("취소") { speech.cancel();partial="";render() })
        content.addView(voice)
        val typed=rememberedInput("typed","예: 엔진오일 교환 8만 원",null,false)
        content.addView(ui.card(ui.section("직접 남기기"),ui.field("정비 내용",typed),
            dashboard().pair(ui.button("해석") { typed.text.toString().takeIf { it.isNotBlank() }?.let { partial=it;interpret(it) } },
                ui.button("항목 골라 입력") { pickItem { manualDraft(it) } })))
        content.addView(ui.section("이렇게 말할 수 있어요"))
        val examplesCard=ui.card()
        examples().forEachIndexed { index,example ->
            if(index>0)examplesCard.addView(ui.divider())
            examplesCard.addView(ui.actionRow(example,null,Icon.MIC) { partial=example;interpret(example) })
        }
        content.addView(examplesCard)
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
        val kmInput = rememberedInput("${d.id}.km", "주행거리 km", d.odometer?.value?.let { it.toBigDecimal().stripTrailingZeros().toPlainString() }, numeric = true)
        val costInput = rememberedInput("${d.id}.cost", "비용 원", d.cost?.toString(), numeric = true)
        val amountInput = rememberedInput("${d.id}.amount", if (d.item == Item.CHARGE) "충전량 kWh (선택)" else "주유량 L (선택)", d.energyAmount?.toString(), numeric = true).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val memoInput = rememberedInput("${d.id}.memo", "메모 (선택)", d.memo, numeric = false)
        val wheels = (editValues["${d.id}.wheels"]?.split(',')?.filter { it.isNotEmpty() }
            ?.map { Wheel.valueOf(it) }?.toSet() ?: d.wheels).toMutableSet()

        val card = ui.card(
            ui.chip("초안 · 확인 후 저장",Palette.accent),
            dashboard().pair(ui.text(d.item.label,32f,bold=true),ui.button("항목 변경") { pickItem { item -> replaceItem(d,item) } }),
            ui.withIcon(ui.button("날짜: ${d.performedOn.format(dateFmt)}") { pickDate(d.performedOn) { service.setDate(d.id,it);editValues.remove("${d.id}.km");render() } },Icon.CALENDAR),
            dashboard().pair(ui.field("주행거리 · km",kmInput),ui.field("비용 · 원",costInput)),
            ui.label(when(d.odometer?.source) {
                Source.VEHICLE -> "차량 수신 거리 · ${d.odometer.receivedAt?.let(timeFmt::format) ?: ""}"
                Source.USER -> "직접 입력한 거리" + (d.odometer.observed?.let { " · 차량 관측 ${km(it.value)} 보존" } ?: "")
                else -> "거리를 비워두면 거리 기준 일정은 계산을 미뤄요."
            }),
        )
        if (d.item == Item.TIRE_REPLACE) {
            val row = ui.row(8)
            fun drawWheels() {
                row.removeAllViews()
                Wheel.entries.forEach { w ->
                    row.addView(ui.button(w.label, primary = w in wheels) {
                        if (!wheels.add(w)) wheels.remove(w)
                        editValues["${d.id}.wheels"] = wheels.joinToString(",") { it.name }
                        drawWheels()
                    })
                }
            }
            drawWheels()
            card.addView(ui.label("교체한 바퀴"))
            card.addView(row)
        }
        if (d.item.kind == ItemKind.ENERGY) card.addView(ui.field(if(d.item==Item.CHARGE)"충전량 · kWh" else "주유량 · L",amountInput))
        card.addView(ui.field("메모 · 선택",memoInput))
        d.detachedObservation?.let { card.addView(ui.label("이전 차량 관측 ${km(it.value)}는 참고값으로 보존했어요. 당시 km를 확인해 주세요.")) }
        d.candidateId?.let { card.addView(ui.button("후보 연결 해제") { service.unlinkCandidate(d.id); render() }) }
        checks.forEach { card.addView(ui.text("· $it", 20f, Palette.sub)) }
        d.spokenText?.let { card.addView(ui.label("인식 텍스트: \"$it\" (원음은 저장하지 않아요)")) }
        card.addView(dashboard().pair(
            ui.button("확인하고 저장",primary=true) { saveDraft(d,kmInput,costInput,wheels,allowDuplicate=false) },
            ui.button("초안 버리기") { service.cancel(d.id);activeDraftId=null;checks=emptyList();message="초안을 버렸어요.";render() }))
        content.addView(card)
    }

    private fun replaceItem(d: DiaryEntry, item: Item) {
        service.replaceItem(d.id, item)
        editValues.remove("${d.id}.amount")
        editValues.remove("${d.id}.wheels")
        render()
    }

    private fun saveDraft(d: DiaryEntry, kmInput: EditText, costInput: EditText, wheels: Set<Wheel>, allowDuplicate: Boolean) {
        if (restricted) return
        val updated = runCatching {
            service.updateEntry(d.id, d.performedOn, number(kmInput.text.toString(), "주행거리"),
                money(costInput.text.toString()), number(editValues["${d.id}.amount"].orEmpty(), "수량"),
                editValues["${d.id}.memo"] ?: d.memo, wheels)
        }
        if (updated.isFailure) { message = updated.exceptionOrNull()?.message; render(); return }
        when (val r = service.confirm(d.id, allowDuplicate)) {
            is DiaryService.ConfirmResult.Saved -> {
                activeDraftId = null
                checks = emptyList()
                partial = ""
                editValues.keys.removeAll { it.startsWith("${d.id}.") || it == "typed" }
                val plan = service.schedules(currentKm()).firstOrNull { it.plan.item == r.entry.item }
                message = buildString {
                    append("${r.entry.item.label} 기록을 저장했어요. 일지 첫걸음 도장을 확인해 보세요.")
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
                    .create().also { tracked(it); it.show() }
                return
            }
            is DiaryService.ConfirmResult.Rejected -> message = r.reason
        }
        render()
    }

    // ------------------------------------------------------------------ 03 자동 기록 후보

    private fun confirmWithoutCost(candidateId: String) {
        if(restricted)return
        tracked(AlertDialog.Builder(this).setTitle("금액 없이 기록할까요?")
            .setMessage("주유·충전 사실만 확인해요. 금액은 미입력으로 남고 나중에 수정할 수 있어요.")
            .setPositiveButton("기록하기") { _,_->
                val d=service.draftFromCandidate(candidateId,user,odometer)
                if(d!=null) {
                    service.setCost(d.id,null)
                    message=when(service.confirm(d.id)) {
                        is DiaryService.ConfirmResult.Saved -> "금액 없이 기록했어요."
                        is DiaryService.ConfirmResult.PossibleDuplicate -> "같은 날 기록이 있어요. 이어서 확인해 주세요."
                        is DiaryService.ConfirmResult.Rejected -> "기록 내용을 다시 확인해 주세요."
                    }
                };render()
            }.setNegativeButton("돌아가기",null).show())
    }

    private fun candidates() {
        content.addView(ui.text("금액을 몰라도 기록할 수 있어요. 미뤄 둔 내용은 여기서 이어가세요.",22f,Palette.sub))
        content.addView(ui.button(if(service.questionQueue(user).muted())"오늘 질문 다시 받기" else "오늘은 묻지 않기") {
            val q=service.questionQueue(user);if(q.muted())q.unmute()else q.muteToday();render()
        })
        val drafts = service.drafts()
        drafts.forEach { d -> content.addView(ui.card(
            ui.text("작성 중 · ${d.item.label}", 26f, bold = true),
            ui.text("${d.performedOn.format(dateFmt)} · ${won(d.cost)}", 22f, Palette.sub),
            ui.button("이어서 확인하기") { activeDraftId = d.id; go(Screen.INPUT) }).apply {
                d.candidateId?.let { cid ->
                    if(d.cost==null)addView(ui.button("금액 없이 기록") { confirmWithoutCost(cid) })
                    addView(ui.button("잘못 감지했어요 · 제외") { service.rejectCandidate(cid);render() })
                }
            }) }
        val kind = service.energyKinds().first()
        val pending = service.unreviewedCandidates()
        if (pending.isEmpty() && drafts.isEmpty()) content.addView(ui.card(ui.text("확인할 기록이 없어요.", color = Palette.sub)))
        pending.forEach { c ->
            val what = if (c.kind == EnergyKind.FUEL) "주유" else "충전"
            content.addView(
                ui.card(
                    ui.chip(if(c.collecting)"추정 · 잔량 변화 관측 중" else "추정 · ${c.state.label}", Palette.warn),
                    ui.text("${what}로 보이는 변화",28f,bold=true),
                    ui.text(CandidateRules.describe(c),24f),
                    ui.label("관측 ${timeFmt.format(c.before.receivedAt)} → ${timeFmt.format(c.after.receivedAt)}"),
                    ui.button("금액 없이 기록") { confirmWithoutCost(c.id) },
                    ui.row().apply {
                        addView(ui.button(if(c.kind==EnergyKind.FUEL)"주유로 기록" else "충전으로 기록", primary = true) {
                            val generation = ++requestGeneration
                            refreshOdometer { r ->
                                if (generation != requestGeneration || restricted || !resumed) return@refreshOdometer
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
        content.addView(ui.card(ui.text("차량 잔량 확인",24f,bold=true),
            ui.text("${kind.label} · ${energyValue?.let { "%.0f%%".format(it) } ?: "미수신"}",28f),
            ui.label(energyStatus),ui.label("잔량이 늘면 확인할 후보를 만들어요. 실제 수량과 금액은 확인 후에 남겨요.")))
        val decided = store.candidates(service.profile().id).filter { it.state != CandidateState.PENDING }.sortedByDescending { it.after.receivedAt }.take(5)
        if (decided.isNotEmpty()) {
            content.addView(ui.card(ui.label("최근 처리한 후보"), *decided.map { ui.label("${timeFmt.format(it.after.receivedAt)} · ${it.kind.label} +%.0f%%p · ${it.state.label}".format(it.rise)) }.toTypedArray()))
        }
    }

    // ------------------------------------------------------------------ 04 일지·비용

    private fun diary() {
        content.addView(ui.actionRow("오늘의 드라이브","운행별 거리·시간·만난 친구",Icon.CAR) { go(Screen.DRIVES) })
        val dashboard = dashboard()
        val ledger = service.ledger(month)
        content.addView(dashboard.monthPicker(month) { month = it; render() })
        content.addView(ui.card(
            ui.text(if (ledger.entryCount > 0 && ledger.missingCostCount == ledger.entryCount) "금액 미입력" else won(ledger.total), 36f, bold = true),
            ui.text("확정 ${ledger.entryCount}건 · 금액 미입력 ${ledger.missingCostCount}건", 22f, Palette.sub),
            ui.text("확인한 기록을 모아두었어요", 22f, Palette.sub)))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("전체", "주유·충전", "정비").forEachIndexed { index, label ->
            filters.addView(shortButton(label, "일지 필터 $label") { diaryFilter = index; render() }.apply {
                setTextColor(if (diaryFilter == index) Palette.onAccent else Palette.text)
                background = ui.round(if (diaryFilter == index) Palette.accent else Palette.surface2, 12)
                setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)); textSize = 20f
                isSelected = diaryFilter == index
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(4) })
        }
        content.addView(filters)
        val timeline = service.timeline().filter { YearMonth.from(it.performedOn) == month && when (diaryFilter) {
            1 -> it.item.kind == ItemKind.ENERGY; 2 -> it.item.kind != ItemKind.ENERGY; else -> true
        } }
        if (timeline.isEmpty()) content.addView(ui.card(ui.text("아직 확정한 기록이 없어요.", color = Palette.sub)))
        val records=ui.card()
        timeline.forEachIndexed { index,e -> if(index>0)records.addView(ui.divider());records.addView(dashboard.recordRow(e) { openEntry(e) }) }
        if(timeline.isNotEmpty())content.addView(records)
        content.addView(ui.button("기록 추가") { activeDraftId = null; go(Screen.INPUT) })
        content.addView(ui.actionRow("정비 리포트 내보내기",null,Icon.EXPORT) { exportReport() })
        content.addView(ui.actionRow("저장한 리포트",null,Icon.JOURNAL) { savedReports() })
    }

    private fun entryDetail() {
        val e = selectedEntryId?.let(service::entry)
        if (e == null) { content.addView(ui.text("이 기록을 열람할 수 없어요.")); return }
        content.addView(ui.card(
            ui.text(e.item.label, 32f, bold = true),
            ui.text(e.performedOn.format(dateFmt), 24f, Palette.sub),
            ui.text(won(e.cost), 40f, Palette.accent, true),
            ui.text("${e.status.label} · 기록 ${e.author}", 22f, Palette.sub)))
        val details = ui.card(
            ui.text("주행거리",22f,Palette.sub),
            ui.text(e.odometer?.let { km(it.value) } ?: "미입력",32f,bold=true),
            ui.label(e.odometer?.source?.label ?: "거리 기준 일정은 계산을 미뤄요"),
            ui.divider(),
            ui.text(if (e.candidateId != null) "자동 감지한 후보를 확인한 기록이에요." else "직접 남긴 기록이에요.", 22f, Palette.sub))
        e.energyAmount?.let { details.addView(ui.text("${if (e.item == Item.CHARGE) "충전량" else "주유량"} $it ${if (e.item == Item.CHARGE) "kWh" else "L"}", 24f)) }
        if (e.wheels.isNotEmpty()) details.addView(ui.text(e.wheels.joinToString { it.label }, 22f))
        details.addView(ui.text("메모",22f,bold=true))
        details.addView(ui.text(e.memo?.takeIf { it.isNotBlank() } ?: "남긴 메모가 없어요.", 22f, Palette.sub))
        content.addView(details)
        if (e.status != EntryStatus.CANCELLED && service.canEdit(e)) content.addView(ui.button("수정하기", primary = true) { entryActions(e) })
        else content.addView(ui.text(if (e.status == EntryStatus.CANCELLED) "취소된 기록은 비용과 관리 일정에서 제외돼요." else "작성자만 수정할 수 있어요.", 22f, Palette.sub))
        if (e.history.isNotEmpty()) content.addView(ui.card(ui.text("변경 이력", 26f, bold = true),
            *e.history.map { ui.text("v${it.version} · ${it.summary}", 22f, Palette.sub) }.toTypedArray()))
    }

    private fun entryActions(e: DiaryEntry) {
        if (restricted || !service.canEdit(e)) return
        var date = e.performedOn
        val kmIn = ui.input("주행거리 km", e.odometer?.value?.let { it.toBigDecimal().stripTrailingZeros().toPlainString() }, true)
        val cost = ui.input("비용 원", e.cost?.toString(), true)
        val amount = ui.input(if (e.item == Item.CHARGE) "충전량 kWh" else "주유량 L", e.energyAmount?.toString(), true).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val memo = ui.input("메모", e.memo, false)
        val dateButton = ui.button("날짜: $date") {}
        dateButton.setOnClickListener { pickDate(date) { picked ->
            if (picked != date && e.odometer?.source == Source.VEHICLE) kmIn.setText("")
            date = picked; dateButton.text = "날짜: $date"
        } }
        val wheels = e.wheels.toMutableSet()
        val body = ui.column().apply {
            addView(dateButton); addView(ui.label("날짜 변경 시 자동 km는 해제돼요. 당시 값을 입력해 주세요."))
            addView(dashboard().pair(ui.field("주행거리 · km",kmIn),ui.field("비용 · 원",cost)))
            if (e.item.kind == ItemKind.ENERGY) addView(ui.field(if(e.item==Item.CHARGE)"충전량 · kWh" else "주유량 · L",amount))
            addView(ui.field("메모 · 선택",memo))
            if (e.item == Item.TIRE_REPLACE) Wheel.entries.forEach { w ->
                addView(ui.checkbox(w.label,w in wheels).apply {
                    setOnCheckedChangeListener { _, checked -> if (checked) wheels.add(w) else wheels.remove(w) }
                })
            }
            addView(ui.label(e.history.joinToString("\n") { "v${it.version} ${it.summary}" }.ifEmpty { "변경 이력 없음" }))
            addView(ui.button("기록 취소") {
                tracked(AlertDialog.Builder(this@MainActivity).setMessage("기록을 취소하면 비용·일정·도장에서 빠져요.")
                    .setPositiveButton("취소하기") { _, _ -> if (!restricted) { service.cancel(e.id); dialogs.toList().forEach { it.dismiss() }; render() } }
                    .setNegativeButton("돌아가기", null).show())
            })
        }
        dialog("${e.item.label} 기록 수정", body) {
            service.updateEntry(e.id, date, number(kmIn.text.toString(), "주행거리"), money(cost.text.toString()),
                number(amount.text.toString(), "수량"), memo.text.toString(), wheels)
            message = "기록을 수정했어요. 비용과 일정을 다시 계산했어요."
            render()
        }
    }

    private fun sharingDialog() {
        val policy = service.sharing(user)
        val everyone = ui.checkbox("차량의 모든 로컬 프로필에 공개",policy.readers==null)
        val readers = ui.input("이름을 쉼표로 구분", policy.readers?.joinToString(", "), false)
        fun updateReaders() { readers.isEnabled=!everyone.isChecked;readers.alpha=if(readers.isEnabled)1f else .45f }
        everyone.setOnCheckedChangeListener { _,_->updateReaders() };updateReaders()
        dialog("내 기록 공유 범위", ui.column().apply {
            addView(ui.label("같은 차량의 로컬 프로필 간 표시 범위예요. 계정 인증이나 원격 공유 기능은 아니에요."))
            addView(everyone); addView(ui.field("열람할 사용자",readers,"쉼표로 구분해요. 비우면 나만 볼 수 있어요."))
            addView(ui.label("내 기존 기록과 앞으로 쓸 기록에 적용해요. 다른 사람은 내 기록을 수정할 수 없어요."))
        }) {
            service.setSharing(user, if (everyone.isChecked) null else readers.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
            render()
        }
    }

    private fun exportReport() {
        var from = service.today().minusYears(1)
        var to = service.today()
        val selected = Item.entries.toMutableSet()
        val fromButton = ui.button("시작일: $from") {}
        val toButton = ui.button("종료일: $to") {}
        fromButton.setOnClickListener { pickDate(from) { from = it; fromButton.text = "시작일: $from" } }
        toButton.setOnClickListener { pickDate(to) { to = it; toButton.text = "종료일: $to" } }
        val memos = ui.checkbox("메모 포함",false)
        val body = ui.column().apply {
            addView(fromButton); addView(toButton); addView(memos)
            addView(ui.label("내가 열람할 수 있는 확정 기록만 내보내요. VIN과 작성자 이름은 제외해요."))
            Item.entries.forEach { item -> addView(ui.checkbox(item.label,true).apply {
                setOnCheckedChangeListener { _, checked -> if (checked) selected.add(item) else selected.remove(item) }
            }) }
        }
        dialog("정비 리포트 범위", body) {
            val report = service.report(from, to, selected, memos.isChecked)
            tracked(AlertDialog.Builder(this).setTitle("리포트 미리보기").setMessage(report)
                .setPositiveButton("파일로 저장") { _, _ -> if (!restricted) {
                    runCatching { saveReport(report) }
                        .onSuccess { message = "리포트를 앱에 저장했어요." }
                        .onFailure { message = "저장하지 못했어요: ${it.message}" }
                    render()
                } }
                .setNeutralButton("공유") { _, _ -> if (!restricted) {
                    runCatching { shareReport(saveReport(report)) }.onFailure { message = "공유하지 못했어요: ${it.message}"; render() }
                } }.setNegativeButton("닫기", null).show())
        }
    }

    private fun reportDirectory(): File {
        val owner = java.security.MessageDigest.getInstance("SHA-256").digest(user.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(filesDir, "reports/$owner").apply { check(isDirectory || mkdirs()) }
    }

    private fun saveReport(report: String): File {
        val file = File(reportDirectory(), "chagok-${service.today()}-${java.util.UUID.randomUUID().toString().take(8)}.txt")
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(report.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        return file
    }

    private fun shareReport(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.reports", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("정비 리포트", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "정비 리포트 파일 공유"))
    }

    private fun savedReports() {
        if (restricted) return
        val files = reportDirectory().listFiles()?.filter { it.extension == "txt" }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) { message = "저장한 리포트가 없어요."; render(); return }
        tracked(AlertDialog.Builder(this).setTitle("내가 저장한 리포트")
            .setItems(files.mapIndexed { index,file -> "정비 리포트 ${files.size-index} · ${java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(file.lastModified()))}" }.toTypedArray()) { _, index ->
                if (!restricted) {
                    val file = files[index]
                    tracked(AlertDialog.Builder(this).setTitle("저장한 정비 리포트").setMessage(file.readText(Charsets.UTF_8))
                        .setPositiveButton("파일 공유") { _, _ -> if (!restricted) {
                            runCatching { shareReport(file) }.onFailure { message = "공유 앱을 열지 못했어요."; render() }
                        } }
                        .setNeutralButton("삭제") { _, _ -> if (!restricted) {
                            message = if (file.delete()) "리포트 파일을 삭제했어요. 일지는 그대로 유지돼요." else "파일을 삭제하지 못했어요."
                            render()
                        } }.setNegativeButton("닫기", null).show())
                }
            }.setNegativeButton("닫기", null).show())
    }

    // ------------------------------------------------------------------ 05 관리 일정

    private fun schedule() {
        val ordered = service.schedules(currentKm())
        val statuses = ordered.associateBy { it.plan.item }
        val urgent = ordered.count { it.state == ScheduleState.DUE || it.state == ScheduleState.SOON }
        content.addView(ui.card(ui.text(if (urgent > 0) "먼저 살펴볼 관리 ${urgent}건" else "다음 교환·점검을 미리 준비해요", 28f, bold = true),
            ui.text("날짜와 거리 중 먼저 도래하는 기준으로 알려드려요. 주기는 차량 매뉴얼에 맞춰 설정해 주세요.", 22f, Palette.sub)))
        val items = service.items().filter { it.kind == ItemKind.REPLACE || it.kind == ItemKind.INSPECT }
            .sortedBy { item -> ordered.indexOfFirst { it.plan.item == item }.let { if (it < 0) Int.MAX_VALUE else it } }
        items.forEach { item ->
            val s = statuses[item]
            val card = ui.card(ui.text(item.label, 28f, bold = true))
            if (s == null) card.addView(ui.text("아직 주기를 설정하지 않았어요.", 22f, Palette.sub))
            else {
                card.addView(ui.text("${s.state.label} · ${DiaryDashboard.remaining(s)}", 26f, stateColor(s.state), true))
                card.addView(dashboard().pair(
                    ui.column(4).apply { addView(ui.label("다음 관리 거리"));addView(ui.text(s.nextKm?.let(::km) ?: if(s.kmState==ConditionState.NOT_SET)"사용 안 함" else "계산 보류",24f,bold=true)) },
                    ui.column(4).apply { addView(ui.label("다음 관리 날짜"));addView(ui.text(s.nextDate?.format(dateFmt) ?: if(s.dateState==ConditionState.NOT_SET)"사용 안 함" else "계산 보류",24f,bold=true)) }))
                card.addView(ui.text("최근 기록: ${s.basis?.let { "${it.performedOn.format(dateFmt)}${it.odometer?.let { o -> " · ${km(o.value)}" } ?: ""}" } ?: "기록 없음"}", 22f, Palette.sub))
                card.addView(ui.text("주기 출처: ${s.plan.source}", 20f, Palette.sub))
                if (!s.plan.notificationsOn) card.addView(ui.text("알림 꺼짐 · 관리 시기는 계속 계산해요", 20f, Palette.sub))
                if (s.odometerReversed) card.addView(ui.text("현재 주행거리가 기준 기록보다 작아요. 차량 변경 등을 확인해 주세요.", 22f, Palette.danger))
                if (s.plan.snoozedUntil?.let { !service.today().isAfter(it) } == true) card.addView(ui.text("${s.plan.snoozedUntil.format(dateFmt)}까지 알림을 미뤘어요", 22f, Palette.sub))
            }
            card.addView(dashboard().pair(
                ui.button("관리 기록하기") { go(Screen.INPUT); manualDraft(item) }.apply { contentDescription = "${item.label} 기록 추가" },
                ui.button("관리 설정") {
                    if (s == null) planDialog(item, null)
                    else tracked(AlertDialog.Builder(this).setTitle(item.label)
                        .setItems(arrayOf("주기 설정", "7일 미루기", if (s.plan.notificationsOn) "알림 끄기" else "알림 켜기")) { _, choice ->
                            if (!restricted) when (choice) {
                                0 -> planDialog(item, s)
                                1 -> { service.snooze(item, service.today().plusDays(7)); render() }
                                2 -> { service.setNotifications(item, !s.plan.notificationsOn); render() }
                            }
                        }.setNegativeButton("닫기", null).show())
                }.apply { contentDescription = "${item.label} 관리 설정" }))
            content.addView(card)
        }
    }

    private fun planDialog(item: Item, s: ScheduleStatus?) {
        val kmIn = ui.input("거리 주기 km", s?.plan?.intervalKm?.toString(), numeric = true)
        val moIn = ui.input("기간 주기 개월", s?.plan?.intervalMonths?.toString(), numeric = true)
        val srcIn = ui.input("예: 차량 매뉴얼", s?.plan?.source, numeric = false)
        dialog("${item.label} 주기", ui.column().apply { addView(dashboard().pair(ui.field("거리 주기 · km",kmIn),ui.field("기간 주기 · 개월",moIn)));addView(ui.label("사용하지 않는 기준은 비워 두세요."));addView(ui.field("주기 출처",srcIn)) }) {
            fun interval(input: EditText): Int? = input.text.toString().let {
                if (it.isBlank()) null else it.toIntOrNull()?.takeIf { v -> v > 0 } ?: error("주기는 양의 정수로 입력해 주세요.")
            }
            service.setPlan(item, interval(kmIn), interval(moIn), srcIn.text.toString())
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
        val progress=stamps.values.count { !it.needsReview }
        content.addView(ui.section("배운 주제 $progress / ${Topic.entries.count { it.available }}","도장은 학습 진행을 나타내요."))
        val progressCard=ui.card()
        Topic.entries.filter { it.available }.forEachIndexed { index,t ->
            if(index>0)progressCard.addView(ui.divider())
            val p=stamps[t]
            progressCard.addView(ui.column(6).apply {
                addView(ui.withIcon(ui.text(t.label,24f,bold=true),if(p!=null&&!p.needsReview)Icon.CHECK else Icon.BOOK))
                addView(ui.label(when { p==null->"아직 배우지 않음";p.needsReview->"근거 기록이 취소되어 다시 확인해요";else->"${timeFmt.format(p.completedAt)} 완료" }))
            })
        }
        content.addView(progressCard)
        val m = mission?.takeIf { cur -> service.missions(user, currentKm()).any { it.topic == cur.topic } }
            ?: service.recommendedMission(user, currentKm())
        mission = m
        if (m == null) {
            content.addView(ui.card(ui.text("지금 추천할 미션이 없어요. 정비 기록을 확정하고 관리 일정을 설정하면 새 미션이 열려요.", color = Palette.sub)))
            return
        }
        val card = ui.card(
            ui.chip("추천 미션 · ${m.topic.label}", Palette.accent),
            ui.text(m.card, 24f),
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
            .setItems(items.map { it.label }.toTypedArray()) { _, i -> if (!restricted) onPick(items[i]) }
            .create().also { tracked(it); it.show() }
    }

    private fun pickDate(initial: LocalDate, onPick: (LocalDate) -> Unit) {
        DatePickerDialog(this, io.chagok.app.R.style.Theme_Chagok_DatePicker, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
            .apply { setTitle("날짜 선택"); datePicker.calendarViewShown=false; datePicker.spinnersShown=true; datePicker.maxDate = System.currentTimeMillis(); tracked(this); show() }
    }

    private fun dialog(title: String, body: View, onOk: () -> Unit) {
        if (restricted) return
        val scroll = ScrollView(this).apply {
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0)
            addView(body)
        }
        val dlg = AlertDialog.Builder(this).setTitle(title)
            .setView(scroll)
            .setPositiveButton("저장", null).setNegativeButton("취소", null).create()
        dlg.setOnShowListener {
            // Automotive 기본 다이얼로그에서도 긴 항목 목록이 하단 버튼을 밀어내지 않게 한다.
            val available = window.decorView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            body.measure(View.MeasureSpec.makeMeasureSpec(scroll.width.coerceAtLeast(ui.dp(300)) - ui.dp(48), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            scroll.layoutParams = scroll.layoutParams.apply { height = minOf(body.measuredHeight + ui.dp(8), (available-ui.dp(420)).coerceAtLeast(ui.dp(180))) }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (restricted) return@setOnClickListener
            runCatching { onOk() }.onSuccess { dlg.dismiss() }
                .onFailure { Toast.makeText(this, it.message ?: "입력 내용을 확인해 주세요.", Toast.LENGTH_LONG).show() }
        } }
        tracked(dlg); dlg.show()
    }

    private fun tracked(d: Dialog): Dialog {
        dialogs += d
        d.setOnDismissListener { dialogs.remove(d) }
        window.decorView.post { if(d.isShowing && d is AlertDialog)ui.styleDialog(d) }
        return d
    }

    private fun rememberedInput(key: String, hint: String, initial: String?, numeric: Boolean): EditText =
        ui.input(hint, editValues[key] ?: initial, numeric).apply {
            // 값은 View가 아닌 화면 상태에 남겨 차량 콜백/화면 재생성에도 보존한다.
            editValues[key] = text.toString()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { editValues[key] = s.toString() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

    private fun number(text: String, label: String): Double? {
        if (text.isBlank()) return null
        return text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: error("$label 값을 확인해 주세요.")
    }
    private fun money(text: String): Long? {
        if (text.isBlank()) return null
        return text.toLongOrNull()?.takeIf { it >= 0 } ?: error("비용은 원 단위 숫자로 입력해 주세요.")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name); outState.putString("draft", activeDraftId)
        outState.putString("partial", partial); outState.putString("month", month.toString())
        outState.putString("entry", selectedEntryId); outState.putInt("filter", diaryFilter)
        outState.putString("detailReturn", detailReturn.name)
        outState.putBundle("edits", Bundle().apply { editValues.forEach { (k, v) -> putString(k, v) } })
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Palette.configure(newConfig)
        windowWidthDp = newConfig.screenWidthDp
        buildShell()
        render()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) handlingTouch = true
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
            handlingTouch = false
            if (renderQueued) { renderQueued = false; render() }
        }
        return handled
    }
}
