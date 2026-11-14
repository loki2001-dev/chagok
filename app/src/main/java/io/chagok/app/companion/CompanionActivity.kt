package io.chagok.app.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.graphics.Color
import android.graphics.Matrix
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.*
import android.widget.*
import io.chagok.app.data.SqliteStore
import io.chagok.app.drive.DrivingState
import io.chagok.app.domain.*
import io.chagok.app.speech.*
import io.chagok.app.ui.MainActivity
import io.chagok.app.ui.Palette
import io.chagok.app.ui.Ui
import io.chagok.app.ui.Icon
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Default launcher: the driving companion, with optional parked access to the diary. */
open class CompanionActivity : Activity() {
    protected open val connectVehicle = true
    protected open val automaticFrames = true
    // Energy amount confirmations wait for parking outside the isolated presentation.
    protected open val deferQuestionsWhileMoving = true
    protected open val automaticQuestions = true
    protected open fun footerMessage(message: String) = message
    lateinit var scene: CompanionSceneView; private set
    lateinit var conversation: EnergyConversation; private set
    private lateinit var repair: RepairConversation
    private var workshop: WorkshopView? = null
    private lateinit var ui: Ui
    private lateinit var store: SqliteStore
    private lateinit var diary: DiaryService
    private lateinit var speech: SpeechInput
    private lateinit var monitor: MonitorConnection
    private var driving: DrivingState? = null
    private var setupRequired = false
    private var setupPowertrain: Powertrain? = null
    private var welcome: FirstRunView? = null
    private var todaySummary = "달리면 오늘의 운행이 자동으로 남아요"
    private var reviewCount = 0
    private var lastSafetyCheck = 0L
    private var pendingMicQuestion: String? = null
    private var pendingMicUser: String? = null
    private var independentParked: Boolean? = null
    private lateinit var body: FrameLayout
    private lateinit var footer: TextView
    private lateinit var status: TextView
    private lateinit var friendsButton: Button
    private lateinit var menuButton: Button
    private lateinit var diaryButton: Button
    private var collection: ScrollView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var state = MonitorState()
    private var user = "나"
    private var resumed = false
    private var listening = false
    private var records = emptyList<EncounterRecord>()
    private var checkedAt = 0L
    private var questionAt = 0L
    private var mileageQuestionKm: Double? = null
    private var mileageHint = ""
    private val awaitingAnswer get() = mileageQuestionKm != null || repair.active || conversation.asking
    private var speechAttempt = 0
    private var toastUntil = 0L
    private var toastText = ""
    private var dialog: AlertDialog? = null
    private var visitDialog = false
    val showingCollection get() = collection?.visibility == View.VISIBLE
    val monitorState get() = state
    private val parked get() = if (!state.enabled) independentParked == true
        else independentParked != false && !state.motion.restricted && (state.motion.speedMps == null || state.motion.speedMps!! < .1f)
    protected open val setupParked get() = driving?.let { it.available && !it.restricted } == true
    protected open fun hasAppPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    protected open val vehicleSpeedGranted get() = !connectVehicle || hasAppPermission("android.car.permission.CAR_SPEED")
    private val needsVehiclePermission get() = state.enabled && !vehicleSpeedGranted
    protected open fun requestAppPermissions(permissions: Array<String>, requestCode: Int) = requestPermissions(permissions,requestCode)
    protected open fun openPermissionSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.configure(resources.configuration); ui = Ui(this)
        store = SqliteStore(this); diary = DiaryService(store)
        setupRequired = FirstRunSetup(store).required
        setupPowertrain = savedInstanceState?.getString("setupPowertrain")?.let { runCatching { Powertrain.valueOf(it) }.getOrNull() }
        if (connectVehicle) driving = DrivingState(this) { Log.i("Chagok", it) }
        user = store.setting("user") ?: "나"; diary.selectUser(user)
        pendingMicQuestion=savedInstanceState?.getString("pendingMicQuestion")
        pendingMicUser=savedInstanceState?.getString("pendingMicUser")
        conversation = EnergyConversation(diary)
        repair = RepairConversation(diary)
        speech = SpeechInput(this, { Log.i("Chagok", it) }) { if (resumed && awaitingAnswer && !listening) listen() }
        scene = CompanionSceneView(this).apply {
            skin = runCatching { CarSkin.valueOf(store.setting("companion.skin/$user") ?: "RACER") }.getOrDefault(CarSkin.RACER)
            collect = { pass ->
                try {
                    store.recordEncounter(diary.profile().id,user,pass.scene.id,pass.token,Instant.now())
                    reloadRecords(); true
                } catch (e: Exception) { Log.e("Chagok", "encounter save failed", e); toastText="저장 공간을 확인해 주세요";toastUntil=SystemClock.elapsedRealtime()+5000;false }
            }
            onEncounter = { toastText=it.name+" · 만남을 남겼어요";toastUntil=SystemClock.elapsedRealtime()+5000 }
        }
        buildShell(); if (!setupRequired) reloadRecords()
        val restored = savedInstanceState?.getBundle("scene")
        if (restored != null) {
            scene.preview=restored.getBoolean("preview")
            scene.restoreTravel(restored.getFloat("travel"))
            scene.previewTurn=restored.getInt("previewTurn");scene.previewRain=restored.getFloat("previewRain")
            scene.previewSpeed=restored.getFloat("previewSpeed",45f)
            scene.director.scenery=runCatching{Scenery.valueOf(restored.getString("scenery")!!)}.getOrDefault(Scenery.CITY)
            scene.director.remainingSeconds=restored.getFloat("remaining",180f)
            scene.director.lastId=restored.getString("last")
            restored.getString("id")?.let(Encounters::find)?.let { e ->
                scene.director.active=EncounterPass(e,restored.getString("token")!!,restored.getFloat("y"),restored.getFloat("seen"),restored.getBoolean("recorded"))
            }
        } else scene.begin(Encounters.all.first())
        monitor = MonitorConnection(this, ::applyMonitorState)
        driving?.observe { applyDrivingSafety(driving?.let { it.available && !it.restricted } == true) }
    }
    private fun requestVehiclePermissions() {
        val wanted=listOf("android.car.permission.CAR_SPEED")+if(Build.VERSION.SDK_INT>=33)listOf(Manifest.permission.POST_NOTIFICATIONS)else emptyList()
        val missing=wanted.filterNot(::hasAppPermission)
        if(missing.isNotEmpty()) {
            if("android.car.permission.CAR_SPEED" in missing)store.saveSetting("permissions.speedRequested","true")
            requestAppPermissions(missing.toTypedArray(),1042)
        }
    }
    private fun recoverVehiclePermission() {
        if (!parked) return
        if (store.setting("permissions.speedRequested")=="true" && !shouldShowRequestPermissionRationale("android.car.permission.CAR_SPEED")) {
            openPermissionSettings()
        } else {
            store.saveSetting("permissions.speedRequested","true")
            requestAppPermissions(arrayOf("android.car.permission.CAR_SPEED"),1042)
        }
    }
    private fun finishSetup(powertrain: Powertrain) {
        driving?.refresh()
        if (!setupRequired || !setupParked) return
        FirstRunSetup(store).complete(powertrain)
        setupRequired = false; welcome = null
        buildShell(); reloadRecords()
        if (connectVehicle) { requestVehiclePermissions(); monitor.open() }
        if (resumed && automaticFrames) scene.resume()
    }
    private fun button(label:String, action:()->Unit)=Button(this).apply {
        text=label;textSize=24f;isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=ui.dp(56);minimumHeight=ui.dp(56)
        typeface=ui.typeface(600);setPadding(ui.dp(12),0,ui.dp(12),0)
        setTextColor(Palette.text);background=ui.touch(Palette.surface,14,Palette.border);setOnClickListener{action()}
    }
    private fun buildShell() {
        if (setupRequired) {
            welcome = FirstRunView(this, setupPowertrain, { setupPowertrain = it }, ::finishSetup)
            setContentView(welcome)
            welcome?.setParked(setupParked)
            return
        }
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Palette.bg)}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        val title=ui.text("차곡",40f,bold=true)
        header.addView(title,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        friendsButton=button("주행으로"){if(parked)hideCollection()}.apply { visibility=View.GONE }
        header.addView(friendsButton,LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT).apply{marginEnd=ui.dp(8)})
        menuButton=button("메뉴"){if(parked)showMenu()};header.addView(menuButton)
        root.addView(header)
        status=ui.text("운행 기록을 준비하고 있어요",26f,Palette.sub)
        root.addView(status)
        body=FrameLayout(this);body.addView(scene,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        root.addView(body,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        footer=ui.text(todaySummary,28f,Palette.sub).apply {
            accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { if (repair.active && safeForTouch()) parkedRepairChoice() }
        }
        root.addView(footer)
        diaryButton=button("차량 일지 · 관리  ›") { if(parked) { if(needsVehiclePermission)recoverVehiclePermission() else openDiary() } }.apply {
            contentDescription="차량 일지와 관리 열기"
            minHeight=ui.dp(64);minimumHeight=ui.dp(64)
            visibility=if(parked)View.VISIBLE else View.GONE
        }
        root.addView(diaryButton,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply {
            marginStart=ui.dp(16);marginEnd=ui.dp(16);bottomMargin=ui.dp(12)
        })
        // React to the actual app window after system insets, not to the full display or a width cap.
        val host=object:FrameLayout(this){
            private var lastWidth = -1
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                val windowWidth=View.MeasureSpec.getSize(widthMeasureSpec)
                if (windowWidth != lastWidth) {
                    lastWidth=windowWidth
                    val widthDp=windowWidth/resources.displayMetrics.density
                    val compact=widthDp<400;val medium=widthDp<600
                    title.textSize=if(compact)28f else if(medium)34f else 40f
                    for (button in listOf(friendsButton,menuButton)) {
                        button.textSize=if(compact)20f else if(medium)22f else 24f
                        val label=if(button===friendsButton)"주행으로" else "메뉴"
                        val textWidth=kotlin.math.ceil(button.paint.measureText(label)).toInt()
                        button.minWidth=textWidth+button.compoundPaddingLeft+button.compoundPaddingRight
                        button.minimumWidth=button.minWidth
                    }
                    status.textSize=if(compact)20f else if(medium)24f else 26f
                    footer.textSize=if(compact)20f else if(medium)24f else 28f
                    diaryButton.textSize=if(compact)22f else 26f
                    val inset=ui.dp(if(compact)16 else 24)
                    header.setPadding(inset,ui.dp(12),inset,ui.dp(4))
                    status.setPadding(inset,ui.dp(4),inset,ui.dp(16))
                    footer.setPadding(inset,ui.dp(16),inset,ui.dp(16))
                }
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            }
        }.apply{setBackgroundColor(Palette.bg);addView(root,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT,Gravity.CENTER_HORIZONTAL))}
        setContentView(host)
    }
    override fun onResume() {
        super.onResume(); resumed=true
        driving?.refresh()
        if (setupRequired) { welcome?.setParked(setupParked); handler.post(tick); return }
        val currentUser=store.setting("user")?:"나"
        if(currentUser!=user){
            pendingMicQuestion=null;pendingMicUser=null
            scene.serviceVisit.cancel()
            conversation.clear();syncQuestion();user=currentUser;diary.selectUser(user);scene.director.active=null
            scene.skin=runCatching{CarSkin.valueOf(store.setting("companion.skin/$user")?:"RACER")}.getOrDefault(CarSkin.RACER)
        }
        reloadRecords();if(automaticFrames)scene.resume()
        if(connectVehicle)monitor.open()
        resumePendingVoiceQuestion()
        handler.post(tick)
    }
    override fun onPause() {
        resumed=false;speechAttempt++;handler.removeCallbacksAndMessages(null);scene.pause();speech.cancel();listening=false
        scene.serviceVisit.cancel()
        mileageQuestionKm = null
        repair.clear(); closeRepairMotion()
        if(conversation.asking)conversation.defer()
        syncQuestion()
        monitor.close();dialog?.dismiss();super.onPause()
    }
    override fun onDestroy() { driving?.release();speech.release();store.close();super.onDestroy() }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig);Palette.configure(newConfig)
        if (setupRequired) { buildShell(); return }
        val reopenCollection=showingCollection
        closeRepairMotion()
        dialog?.dismiss();(scene.parent as? android.view.ViewGroup)?.removeView(scene);collection=null
        buildShell();updateStatus()
        if(reopenCollection)showCollection()else if(resumed&&automaticFrames)scene.resume()
    }
    protected fun applyMonitorState(value:MonitorState) {
        state=value;scene.motion=value.motion
        if (mileageQuestionKm != null && (!value.enabled || safeForTouch())) finishMileageQuestion()
        if (!safeForTouch() || !state.motion.animationAllowed) closeRepairMotion()
        scene.fuelVisit.observe(value.fuelVisitId, value.motion)
        if(!state.enabled)scene.serviceVisit.cancel()
        if(visitDialog && (state.motion.restricted || state.motion.parkGear != true || state.motion.speedMps?.let { kotlin.math.abs(it) < .3f } != true)) dialog?.dismiss()
        enforceParkedUi()
        resumePendingVoiceQuestion()
    }
    /** UX restrictions must close parked surfaces even when recording (and its callbacks) is off. */
    protected fun applyDrivingSafety(isParked: Boolean) {
        independentParked=isParked
        if (setupRequired) { welcome?.setParked(setupParked); return }
        if (::menuButton.isInitialized) { enforceParkedUi();resumePendingVoiceQuestion() }
    }
    private fun enforceParkedUi() {
        if(!parked){
            if(conversation.asking && deferQuestionsWhileMoving) { speechAttempt++;speech.cancel();listening=false;conversation.defer();questionAt=SystemClock.elapsedRealtime();syncQuestion() }
            dialog?.dismiss()
            if(showingCollection)hideCollection()
            scene.serviceVisit.cancel()
            if(scene.preview){scene.preview=false;scene.director.active=null;scene.director.remainingSeconds=180f}
        }
        updateStatus()
    }
    private fun safeForTouch() = !state.motion.restricted && state.motion.parkGear == true &&
        state.motion.speedMps?.let { kotlin.math.abs(it) < .3f } == true

    private fun parkedRepairChoice() {
        if (dialog?.isShowing == true) return
        val items = repair.repairs()
        val picking = repair.choosing
        val choices = if (picking) items.map { it.label } else listOf("네", "아니요", "나중에")
        dialog = AlertDialog.Builder(this).setTitle(repair.question)
            .setItems(choices.toTypedArray()) { _, index ->
                if (repair.active && safeForTouch()) {
                    if (picking) completeRepair { repair.save(items[index]) } else receiveAnswer(choices[index])
                }
            }.show()
    }

    private val receiptKey get() = "workshop.receipt/${diary.profile().id}/$user"
    private fun closeRepairMotion() {
        workshop?.let { (it.parent as? FrameLayout)?.removeView(it) }
        workshop = null
    }
    private fun showSavedRepairWhenParked() {
        if (!safeForTouch() || !state.motion.animationAllowed || workshop != null || showingCollection || scene.preview) return
        val id = store.setting(receiptKey)?.takeIf { it.isNotBlank() } ?: return
        val entry = diary.entry(id)?.takeIf { it.status == EntryStatus.CONFIRMED }
        val job = entry?.let { BayJob.forItem(it.item) }
        if (entry == null || job == null) {
            store.saveSetting(receiptKey, "")
            return
        }
        workshop = WorkshopView(this, job, scene.skin, entry.wheels, entry.engineOil?.manufacturer,
            entry.engineOil?.liters, entry.item.label, finished = {
                if (store.setting(receiptKey) == id) store.saveSetting(receiptKey, "")
                closeRepairMotion()
            }).also {
            body.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }
    private fun updateStatus() {
        if (setupRequired) return
        friendsButton.isEnabled=parked;menuButton.isEnabled=parked
        friendsButton.visibility=if(showingCollection)View.VISIBLE else View.GONE
        diaryButton.visibility=if(parked)View.VISIBLE else View.GONE
        diaryButton.text=if(needsVehiclePermission)"차량 권한 허용하기" else if(reviewCount>0)"확인할 기록 ${reviewCount}건  ›" else "차량 일지 · 관리  ›"
        diaryButton.contentDescription=if(needsVehiclePermission)"운행 기록을 위한 차량 권한 허용하기" else if(reviewCount>0)"확인할 기록 ${reviewCount}건 열기" else "차량 일지와 관리 열기"
        status.text=when{
            scene.preview->"미리보기 · 기록되지 않아요"
            !state.enabled->"자동 기록 꺼짐"
            needsVehiclePermission->"운행 기록 준비 미완료 · 차량 권한 필요"
            state.motion.speedMps==null->"차량 속도 미수신 · 권한과 연결을 확인해 주세요"
            state.motion.speedMps!!>.3f->"오늘의 운행을 기록하고 있어요"
            else->"정차 중 · ${state.recordingStatus}"
        }
    }
    private val tick=object:Runnable{
        override fun run(){
            if(!resumed)return
            val now=SystemClock.elapsedRealtime()
            if (now-lastSafetyCheck>=5000) { lastSafetyCheck=now;driving?.refresh() }
            if (setupRequired) { welcome?.setParked(setupParked);handler.postDelayed(this,200);return }
            if(now-checkedAt>=1000){
                checkedAt=now
                val candidates=diary.pendingCandidates()
                showSavedRepairWhenParked()
                refreshSummary()
                syncServiceVisit()
                resumePendingVoiceQuestion()
                if(automaticQuestions&&state.enabled&&!scene.preview&&!showingCollection&&scene.serviceVisit.frame==null&&!repair.active&&workshop==null&&!conversation.asking&&conversation.stage==EnergyConversation.Stage.IDLE){
                    val audio=getSystemService(android.media.AudioManager::class.java)
                    val safe = parked && state.motion.parkGear == true && state.motion.speedMps?.let { kotlin.math.abs(it) < .3f } == true
                    val queue = diary.questionQueue(user)
                    val visits = if(store.setting(PlaceVisitTracker.ENABLED) == "true") diary.pendingVisits(user) else emptyList()
                    val departed = visits.lastOrNull { it.place.kind == PlaceKind.REPAIR && it.exited &&
                        store.setting(askedKey("visit/${it.id}")) != "true" }
                    val visit = visits.firstOrNull { it.place.kind == PlaceKind.FUEL }
                    if(dialog?.isShowing != true && audio.mode==android.media.AudioManager.MODE_NORMAL) {
                        if(departed != null && !queue.muted() && state.motion.speedMps != null && state.motion.parkGear != null) {
                            if (mileageQuestionKm != null) finishMileageQuestion()
                            queue.offered("visit/${departed.id}")
                            store.saveSetting(askedKey("visit/${departed.id}"),"true")
                            repair.begin(departed); questionAt = now; syncQuestion(); listen()
                        } else if(mileageQuestionKm == null && visit != null && queue.canOffer(safe)) {
                            queue.offered("visit/${visit.id}"); visitDialog = true
                            dialog = AlertDialog.Builder(this@CompanionActivity)
                                .setTitle("${visit.place.name}에 들르셨나요?")
                                .setMessage("${visit.place.kind.label} 근처 정차가 감지됐어요. 실제 이용 여부를 확인하고 일지를 남겨 주세요.")
                                .setPositiveButton("확인하기") { _, _ -> startActivity(Intent(this@CompanionActivity, io.chagok.app.ui.PlacesActivity::class.java)) }
                                .setNegativeButton("아니요") { _, _ -> diary.rejectVisit(visit.id, user) }
                                .setNeutralButton("나중에", null).create().also { it.setOnDismissListener { visitDialog = false }; it.show() }
                        } else if(mileageQuestionKm == null && speech.isAvailable() && hasAppPermission(Manifest.permission.RECORD_AUDIO)) {
                            val candidate = queue.next(candidates, safe)
                            if (candidate != null) beginQuestion(candidate.id)
                            else if (!queue.muted() && state.motion.parkGear == false &&
                                (state.motion.speedMps ?: 0f) > .3f) {
                                val current = diary.currentKm(state.odometer)
                                if (current != null && diary.offerServiceMileage()) {
                                    mileageQuestionKm = current
                                    mileageHint = "예를 들어 ‘4만 2천 킬로’"
                                    questionAt = now
                                    syncQuestion()
                                    listen()
                                }
                            }
                        }
                    }
                }
            }
            if(repair.active && now-questionAt>=25_000) completeRepair { repair.receive("나중에") }
            if (mileageQuestionKm != null && now-questionAt >= 25_000) finishMileageQuestion()
            if(conversation.asking&&now-questionAt>=20_000){speech.cancel();listening=false;conversation.defer();questionAt=now;syncQuestion()}
            if(conversation.stage==EnergyConversation.Stage.FINISHED&&now-questionAt>=5000){conversation.clear();syncQuestion()}
            footer.isClickable = repair.active && safeForTouch()
            footer.text=footerMessage(when{
                needsVehiclePermission->"운행을 기록하려면 차량 권한이 필요해요.\n이미 남긴 일지는 메뉴에서 볼 수 있어요."
                scene.serviceVisit.frame!=null->"${scene.serviceVisit.frame!!.caption}\n방문 기념 연출 · 실제 이용 내역은 확인 후 기록해요."
                scene.preview->"미리보기 중이에요.\n친구는 실제 기록에 남지 않아요."
                repair.active -> if(safeForTouch()) "말하거나 여기를 눌러 답해주세요" else "음성으로 답해주세요 · 나중에 확인할 수 있어요"
                mileageQuestionKm != null -> "음성으로 답해주세요.\n기억이 안 나면 ‘몰라요’라고 하세요."
                conversation.asking->if(listening)"답변을 듣고 있어요.\n금액은 확인한 뒤 기록해요." else "음성 연결을 확인하고 있어요.\n응답이 없으면 초안으로 남겨요."
                toastUntil>now->toastText
                !state.enabled->"자동 기록이 꺼져 있어요.\n메뉴에서 다시 켤 수 있어요."
                state.motion.speedMps==null->"차량 연결을 확인하고 있어요.\n연결되면 운행 기록을 시작해요."
                reviewCount>0->"$todaySummary\n${if(parked)"확인할 기록" else "정차 후 확인할 기록"} ${reviewCount}건"
                else->"$todaySummary\n${if(parked)"필요한 금액만 확인하면 기록이 완성돼요" else "금액 확인은 정차 후에 할게요"}"
            })
            handler.postDelayed(this,200)
        }
    }
    private fun refreshSummary() {
        todaySummary=CompanionSummary.today(store.trips(diary.profile().id),user,diary.today(),ZoneId.systemDefault())
        reviewCount=diary.reviewCount()
        updateStatus()
    }
    private fun openDiary() {
        startActivity(Intent(this,MainActivity::class.java).apply {
            if(reviewCount>0)putExtra("openReview",true)
        })
    }
    private fun serviceVisitKey(visit: PlaceVisit) = "companion.service-visit/${visit.vehicleId}/${visit.user}/${visit.id}"
    private fun syncServiceVisit() {
        if(scene.preview)return
        if(!state.enabled || store.setting(PlaceVisitTracker.ENABLED)!="true" || !ServiceVisitAnimation.safe(state.motion)) {
            scene.serviceVisit.cancel();return
        }
        val profile=diary.profile()
        val now=Instant.now()
        val current=store.visits(profile.id).filter {
            ServiceVisitAnimation.eligible(it,profile.id,user,now,Item.REFUEL.availableFor(profile.powertrain))
        }
        val active=scene.serviceVisit.frame
        if(active!=null) {
            if(current.singleOrNull()?.id!=active.visitId)scene.serviceVisit.cancel()
            return
        }
        if(showingCollection || conversation.asking || dialog?.isShowing==true)return
        // One unambiguous current stop only; old unanswered candidates belong to the inbox.
        val visit=current.singleOrNull()?:return
        if(store.setting(serviceVisitKey(visit))=="true")return
        // Persist before starting so rotation/process recreation cannot replay the same arrival.
        store.saveSetting(serviceVisitKey(visit),"true")
        scene.serviceVisit.begin(visit.id,visit.place.kind)
    }
    private fun askedKey(id:String)="companion.asked/${diary.profile().id}/$user/$id"
    fun beginQuestion(candidateId:String):Boolean {
        if(setupRequired||repair.active||scene.preview||!resumed||mileageQuestionKm!=null)return false
        if(!conversation.begin(candidateId,user,state.odometer))return false
        store.saveSetting(askedKey(candidateId),"true");questionAt=SystemClock.elapsedRealtime();speechAttempt++
        syncQuestion();listen();return true
    }
    protected fun requestVoiceQuestion(candidateId: String) {
        if (!parked) return
        pendingMicQuestion=candidateId;pendingMicUser=user
        if (!hasAppPermission(Manifest.permission.RECORD_AUDIO)) {
            requestAppPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),1043)
        } else resumePendingVoiceQuestion()
    }
    private fun resumePendingVoiceQuestion() {
        val candidate=pendingMicQuestion ?: return
        if(pendingMicUser!=user || pendingMicUser!=(store.setting("user") ?: "나")) {
            pendingMicQuestion=null;pendingMicUser=null;return
        }
        if(!resumed || setupRequired || !parked || scene.preview || showingCollection || conversation.asking ||
            dialog?.isShowing==true || !hasAppPermission(Manifest.permission.RECORD_AUDIO))return
        // Consume only when the Activity can actually begin; permission UI may still have it paused.
        pendingMicQuestion=null;pendingMicUser=null
        beginQuestion(candidate)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,results)
        if(requestCode==1043) {
            if(results.firstOrNull()==PackageManager.PERMISSION_GRANTED) {
                resumePendingVoiceQuestion()
            } else {
                pendingMicQuestion=null;pendingMicUser=null
                toastText="마이크 없이도 차량 일지에서 직접 입력할 수 있어요"
                toastUntil=SystemClock.elapsedRealtime()+5000
            }
        }
        if(requestCode==1042 && !setupRequired)updateStatus()
    }
    private fun syncQuestion(){
        scene.question = when {
            repair.active -> repair.question
            mileageQuestionKm != null -> "마지막으로 정비한\n주행거리가 몇 km였나요?"
            else -> conversation.text
        }
        scene.questionHint = when {
            repair.active -> repair.hint
            mileageQuestionKm != null -> mileageHint
            else -> conversation.hint
        }
        scene.questionContext = when {
            mileageQuestionKm != null -> "차곡 · 첫 정비 일정"
            conversation.stage == EnergyConversation.Stage.CONFIRM -> "한 번만 확인할게요"
            else -> "차곡 · 자동 기록"
        }
        scene.invalidate()
    }
    private fun listen(){
        if(!connectVehicle||!resumed||!awaitingAnswer||listening||!speech.isAvailable())return
        if(!hasAppPermission(Manifest.permission.RECORD_AUDIO))return
        val token=++speechAttempt;listening=true
        speech.listen(object:SpeechCallbacks{
            override fun onState(state:ListenState){if(token==speechAttempt)listening=state!=ListenState.IDLE}
            override fun onPartial(text:String)=Unit // Do not display live transcription on the driving screen.
            override fun onFinal(text:String){if(token==speechAttempt&&resumed){listening=false;receiveAnswer(text)}}
            override fun onFailed(reason:String){if(token==speechAttempt&&resumed){
                listening=false
                if (mileageQuestionKm != null) finishMileageQuestion()
                else if(!repair.active) { conversation.defer();questionAt=SystemClock.elapsedRealtime() }
                syncQuestion()
            }}
        })
    }
    fun receiveAnswer(text:String){
        if (repair.active) { completeRepair { repair.receive(text) }; return }
        mileageQuestionKm?.let { initialKm ->
            val current = diary.currentKm(state.odometer) ?: initialKm
            val km = UtteranceParser.serviceMileage(text)
            when {
                listOf("몰라", "모르", "기억", "나중", "취소", "건너").any(text::contains) -> finishMileageQuestion()
                km != null && km <= current -> {
                    diary.setReportedServiceKm(km, current)
                    finishMileageQuestion("${DiaryService.km(km)}를 첫 정비 일정의 기준으로 저장했어요")
                }
                else -> {
                    mileageHint = if (km == null) "거리를 다시 말해주세요 · 예: 4만 킬로"
                        else "현재 ${DiaryService.km(current)} 이하로 말해주세요"
                    syncQuestion()
                    handler.postDelayed({ if (mileageQuestionKm != null) listen() }, 450)
                }
            }
            return
        }
        conversation.receive(text);questionAt=SystemClock.elapsedRealtime();syncQuestion()
        if(conversation.asking)handler.postDelayed({listen()},450)
    }
    private fun finishMileageQuestion(message: String = "차량 요약에서 마지막 정비 주행거리를 입력할 수 있어요") {
        speechAttempt++
        speech.cancel()
        listening = false
        mileageQuestionKm = null
        toastText = message
        toastUntil = SystemClock.elapsedRealtime() + 5000
        syncQuestion()
    }
    private fun completeRepair(answer: () -> String?) {
        speechAttempt++; speech.cancel(); listening = false
        dialog?.dismiss()
        val message = runCatching(answer).getOrElse { repair.clear(); "차량 일지에서 방문 기록을 확인해주세요" }
        questionAt = SystemClock.elapsedRealtime()
        if (message != null) { toastText = message; toastUntil = questionAt + 5000 }
        repair.takeSaved()?.let { store.saveSetting(receiptKey, it.id) }
        syncQuestion()
        if (repair.active) handler.postDelayed({ listen() }, 450)
    }
    private fun reloadRecords(){records=store.encounters(diary.profile().id,user);scene.seen=records.map{it.id}.toSet();if(::friendsButton.isInitialized)refreshSummary()}
    private fun hideCollection(){collection?.let{body.removeView(it)};collection=null;scene.visibility=View.VISIBLE;if(resumed&&automaticFrames)scene.resume();updateStatus()}
    protected fun showCollection(){
        if(!parked)return
        collection?.let{body.removeView(it)};scene.pause();scene.visibility=View.GONE
        val list=ui.column(16).apply { setPadding(ui.dp(20),ui.dp(20),ui.dp(20),ui.dp(24)) }
        list.addView(ui.section("만난 친구들","가상의 작은 만남 · ${records.size}/10"))
        val width=(body.width/resources.displayMetrics.density).toInt().takeIf { it>0 } ?: resources.configuration.screenWidthDp
        val columns=if(width<550)1 else if(width<1100)2 else 3
        Encounters.all.chunked(columns).forEach{pair->
            val row=LinearLayout(this)
            pair.forEach{e->
                val record=records.firstOrNull{it.id==e.id}
                val card=ui.card()
                if(record==null)card.addView(FrameLayout(this).apply {
                    background=ui.round(Palette.bg,12)
                    addView(ui.icon(Icon.FRIENDS,56,Palette.sub),FrameLayout.LayoutParams(ui.dp(56),ui.dp(56),Gravity.CENTER))
                },LinearLayout.LayoutParams(MATCH_PARENT,ui.dp(160)))
                else{
                    val image=ImageView(this).apply{
                        setImageBitmap(scene.artwork(e.id));scaleType=ImageView.ScaleType.MATRIX;contentDescription=e.name
                        addOnLayoutChangeListener{v,_,_,_,_,_,_,_,_->
                            val bitmap=scene.artwork(e.id);val scale=v.width*3.4f/bitmap.width
                            imageMatrix=Matrix().apply{setScale(scale,scale);postTranslate((v.width*.5f-(if(e.left).16f else .85f)*bitmap.width*scale).coerceIn(v.width-bitmap.width*scale,0f),v.height*.5f-bitmap.height*.49f*scale)}
                        }
                    }
                    card.addView(image,LinearLayout.LayoutParams(MATCH_PARENT,ui.dp(180)))
                }
                card.addView(ui.text(if(record==null)"아직 만나지 못했어요"else e.name,24f,bold=true))
                card.addView(ui.text(record?.let{"${it.count}번 만남 · ${DateTimeFormatter.ofPattern("M.d").withZone(ZoneId.systemDefault()).format(it.last)}"}?:"다음 길에서 만나요",20f,Palette.sub))
                row.addView(card,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{setMargins(ui.dp(4),0,ui.dp(4),0)})
            };list.addView(row)
        }
        collection=ScrollView(this).apply{addView(list);setBackgroundColor(Palette.bg)}.also{body.addView(it,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))}
        updateStatus()
    }
    private fun showMenu(){
        val options=if(scene.preview)arrayOf("다음 만남", "급가속", "왼쪽 / 오른쪽", "비 효과", "미리보기 끝내기", "주유소 방문", "정비소 방문")
            else arrayOf("차량 스킨", "일지·차량 관리", "만난 친구들 · ${records.size}/10", "말로 기록 확인", "주행 연출 미리보기", "차량 연결 권한", if(store.setting(RecordingStartup.ENABLED)=="false")"자동 기록 켜기" else "자동 기록 종료", if(diary.questionQueue(user).muted())"오늘 질문 다시 받기" else "오늘은 묻지 않기")
        dialog=AlertDialog.Builder(this).setTitle(if(scene.preview)"주행 연출 미리보기"else "차곡").setItems(options){_,index->
            if(!parked)return@setItems
            if(scene.preview){when(index){
                0->{scene.serviceVisit.cancel();scene.previewSpeed=45f;val next=(Encounters.all.indexOf(scene.director.active?.scene)+1)%Encounters.all.size;scene.begin(Encounters.all[next],true)}
                1->{scene.serviceVisit.cancel();scene.previewSpeed=85f;scene.previewAcceleration=3f;handler.postDelayed({scene.previewAcceleration=0f},2500)}
                2->{scene.serviceVisit.cancel();scene.previewSpeed=45f;scene.previewTurn=if(scene.previewTurn<0)1 else -1}
                3->scene.previewRain=if(scene.previewRain>.1f)0f else 1f
                4->{scene.serviceVisit.cancel();scene.preview=false;scene.director.active=null;scene.director.remainingSeconds=180f;scene.director.scenery=Scenery.CITY}
                5,6->{
                    if(scene.previewService(if(index==5)PlaceKind.FUEL else PlaceKind.REPAIR))hideCollection()
                    else Toast.makeText(this,"P단 정차 신호를 확인한 뒤 미리 볼 수 있어요",Toast.LENGTH_SHORT).show()
                }
            }}else when(index){
                0->showSkins()
                1->startActivity(Intent(this,MainActivity::class.java))
                2->showCollection()
                3->{conversation.clear();val candidate=diary.pendingCandidates().firstOrNull { !it.collecting };if(candidate!=null)requestVoiceQuestion(candidate.id)else Toast.makeText(this,"지금 확인할 주유·충전 기록이 없어요",Toast.LENGTH_SHORT).show()}
                4->{scene.serviceVisit.cancel();speech.cancel();conversation.defer();conversation.clear();syncQuestion();hideCollection();scene.preview=true;scene.previewSpeed=45f;scene.previewAcceleration=0f;scene.begin(Encounters.all.first(),true)}
                5->requestVehiclePermissions()
                6->{val enable=store.setting(RecordingStartup.ENABLED)=="false";store.saveSetting(RecordingStartup.ENABLED,enable.toString());monitor.close();if(!enable)stopService(Intent(this,VehicleMonitorService::class.java));monitor.open()}
                7->{val queue=diary.questionQueue(user);if(queue.muted())queue.unmute() else {queue.muteToday();speech.cancel();conversation.defer();syncQuestion()}}
            };updateStatus()
        }.setNegativeButton("닫기",null).show().also { ui.styleDialog(it) }
    }
    private fun showSkins(){dialog=AlertDialog.Builder(this).setTitle("차량 스킨").setSingleChoiceItems(arrayOf("네온 레이서","현대 세단"),scene.skin.ordinal){d,index->
        if(parked){scene.skin=CarSkin.entries[index];store.saveSetting("companion.skin/$user",scene.skin.name);scene.invalidate()};d.dismiss()
    }.setNegativeButton("닫기",null).show().also { ui.styleDialog(it) }}
    override fun onSaveInstanceState(outState:Bundle){
        outState.putString("pendingMicQuestion",pendingMicQuestion)
        outState.putString("pendingMicUser",pendingMicUser)
        setupPowertrain?.let { outState.putString("setupPowertrain",it.name) }
        outState.putBundle("scene",Bundle().apply{
            putBoolean("preview",scene.preview);putString("scenery",scene.director.scenery.name);putFloat("remaining",scene.director.remainingSeconds);putString("last",scene.director.lastId)
            putFloat("travel",scene.travel);putInt("previewTurn",scene.previewTurn);putFloat("previewRain",scene.previewRain);putFloat("previewSpeed",scene.previewSpeed)
            scene.director.active?.let{putString("id",it.scene.id);putString("token",it.token);putFloat("y",it.y);putFloat("seen",it.seenSeconds);putBoolean("recorded",it.recorded)}
        });super.onSaveInstanceState(outState)
    }
    @Deprecated("Android framework back dispatch")
    override fun onBackPressed(){when{showingCollection->hideCollection();scene.preview->{scene.serviceVisit.cancel();scene.preview=false;scene.director.active=null;updateStatus()};else->super.onBackPressed()}}
}
