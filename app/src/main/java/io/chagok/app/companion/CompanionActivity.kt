package io.chagok.app.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.*
import android.widget.*
import io.chagok.app.data.SqliteStore
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
    // Production always defers while moving. Only the isolated emulator presentation overrides this.
    protected open val deferQuestionsWhileMoving = true
    protected open val automaticQuestions = true
    protected open fun footerMessage(message: String) = message
    lateinit var scene: CompanionSceneView; private set
    lateinit var conversation: EnergyConversation; private set
    private lateinit var ui: Ui
    private lateinit var store: SqliteStore
    private lateinit var diary: DiaryService
    private lateinit var speech: SpeechInput
    private lateinit var monitor: MonitorConnection
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
    private var pendingCount = 0
    private var checkedAt = 0L
    private var questionAt = 0L
    private var speechAttempt = 0
    private var toastUntil = 0L
    private var toastText = ""
    private var dialog: AlertDialog? = null
    private var visitDialog = false
    val showingCollection get() = collection?.visibility == View.VISIBLE
    val monitorState get() = state
    private val parked get() = !state.motion.restricted && (state.motion.speedMps == null || state.motion.speedMps!! < .1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.configure(resources.configuration); ui = Ui(this)
        store = SqliteStore(this); diary = DiaryService(store)
        user = store.setting("user") ?: "나"; diary.selectUser(user)
        conversation = EnergyConversation(diary)
        speech = SpeechInput(this, { Log.i("Chagok", it) }) { if (resumed && conversation.asking && !listening) listen() }
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
        buildShell(); reloadRecords()
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
        if (connectVehicle) requestVehiclePermissions()
    }
    private fun requestVehiclePermissions() {
        val wanted=listOf("android.car.permission.CAR_SPEED",Manifest.permission.RECORD_AUDIO)+if(Build.VERSION.SDK_INT>=33)listOf(Manifest.permission.POST_NOTIFICATIONS)else emptyList()
        val missing=wanted.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isNotEmpty())requestPermissions(missing.toTypedArray(),1042)
    }
    private fun button(label:String, action:()->Unit)=Button(this).apply {
        text=label;textSize=24f;isAllCaps=false;minWidth=0;minimumWidth=0;minHeight=ui.dp(56);minimumHeight=ui.dp(56)
        typeface=ui.typeface(600);setPadding(ui.dp(12),0,ui.dp(12),0)
        setTextColor(Palette.text);background=ui.touch(Palette.surface,14,Palette.border);setOnClickListener{action()}
    }
    private fun buildShell() {
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Palette.bg)}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        val title=ui.text("차곡",40f,bold=true)
        header.addView(title,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        friendsButton=button("친구 0/10"){if(parked)toggleCollection()}
        header.addView(friendsButton,LinearLayout.LayoutParams(WRAP_CONTENT,WRAP_CONTENT).apply{marginEnd=ui.dp(8)})
        menuButton=button("메뉴"){if(parked)showMenu()};header.addView(menuButton)
        root.addView(header)
        status=ui.text("차량 연결 중",26f,Palette.sub)
        root.addView(status)
        body=FrameLayout(this);body.addView(scene,FrameLayout.LayoutParams(MATCH_PARENT,MATCH_PARENT))
        root.addView(body,LinearLayout.LayoutParams(MATCH_PARENT,0,1f))
        footer=ui.text("만난 친구는 자동으로 남겨요.",28f,Palette.sub).apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
        root.addView(footer)
        diaryButton=button("차량 일지 · 관리  ›") { if(parked) startActivity(Intent(this,MainActivity::class.java)) }.apply {
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
                        val label=if(button===friendsButton)"친구 10/10" else "메뉴"
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
        val currentUser=store.setting("user")?:"나"
        if(currentUser!=user){
            conversation.clear();syncQuestion();user=currentUser;diary.selectUser(user);scene.director.active=null
            scene.skin=runCatching{CarSkin.valueOf(store.setting("companion.skin/$user")?:"RACER")}.getOrDefault(CarSkin.RACER)
        }
        reloadRecords();if(automaticFrames)scene.resume()
        if(connectVehicle)monitor.open()
        handler.post(tick)
    }
    override fun onPause() {
        resumed=false;speechAttempt++;handler.removeCallbacksAndMessages(null);scene.pause();speech.cancel();listening=false
        if(conversation.asking){conversation.defer();syncQuestion()}
        monitor.close();dialog?.dismiss();super.onPause()
    }
    override fun onDestroy() { speech.release();store.close();super.onDestroy() }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig);Palette.configure(newConfig)
        val reopenCollection=showingCollection
        dialog?.dismiss();(scene.parent as? android.view.ViewGroup)?.removeView(scene);collection=null
        buildShell();updateStatus()
        if(reopenCollection)showCollection()else if(resumed&&automaticFrames)scene.resume()
    }
    protected fun applyMonitorState(value:MonitorState) {
        state=value;scene.motion=value.motion
        if(visitDialog && (state.motion.restricted || state.motion.parkGear != true || state.motion.speedMps?.let { kotlin.math.abs(it) < .3f } != true)) dialog?.dismiss()
        if(!parked){
            if(conversation.asking && deferQuestionsWhileMoving) { speech.cancel();listening=false;conversation.defer();questionAt=SystemClock.elapsedRealtime();syncQuestion() }
            dialog?.dismiss()
            if(showingCollection)hideCollection()
            if(scene.preview){scene.preview=false;scene.director.active=null;scene.director.remainingSeconds=180f}
        }
        updateStatus()
    }
    private fun updateStatus() {
        friendsButton.isEnabled=parked;menuButton.isEnabled=parked
        diaryButton.visibility=if(parked)View.VISIBLE else View.GONE
        friendsButton.text=if(showingCollection)"주행으로" else "친구 ${records.size}/10"
        status.text=when{
            scene.preview->"미리보기 · 기록되지 않아요"
            !state.enabled->"자동 기록 꺼짐"
            state.motion.speedMps==null->"차량 속도 미수신 · 권한과 연결을 확인해 주세요"
            !state.motion.animationAllowed->"자동 기록 중 · 주행 제한에 맞춰 연출을 쉬고 있어요"
            state.motion.speedMps!!>.3f->"함께 가는 중"
            else->"잠시 쉬는 중 · ${state.recordingStatus}"
        }
    }
    private val tick=object:Runnable{
        override fun run(){
            if(!resumed)return
            val now=SystemClock.elapsedRealtime()
            if(now-checkedAt>=1000){
                checkedAt=now
                val candidates=diary.pendingCandidates();pendingCount=candidates.size
                if(automaticQuestions&&state.enabled&&!scene.preview&&!showingCollection&&!conversation.asking&&conversation.stage==EnergyConversation.Stage.IDLE){
                    val audio=getSystemService(android.media.AudioManager::class.java)
                    val safe = parked && state.motion.parkGear == true && state.motion.speedMps?.let { kotlin.math.abs(it) < .3f } == true
                    val queue = diary.questionQueue(user)
                    val visit = if(store.setting(PlaceVisitTracker.ENABLED) == "true") diary.pendingVisits(user).firstOrNull() else null
                    if(dialog?.isShowing != true && audio.mode==android.media.AudioManager.MODE_NORMAL) {
                        if(visit != null && queue.canOffer(safe)) {
                            queue.offered("visit/${visit.id}"); visitDialog = true
                            dialog = AlertDialog.Builder(this@CompanionActivity)
                                .setTitle("${visit.place.name}에 들르셨나요?")
                                .setMessage("${visit.place.kind.label} 근처 정차가 감지됐어요. 실제 이용 여부를 확인하고 일지를 남겨 주세요.")
                                .setPositiveButton("확인하기") { _, _ -> startActivity(Intent(this@CompanionActivity, io.chagok.app.ui.PlacesActivity::class.java)) }
                                .setNegativeButton("아니요") { _, _ -> diary.rejectVisit(visit.id, user) }
                                .setNeutralButton("나중에", null).create().also { it.setOnDismissListener { visitDialog = false }; it.show() }
                        } else if(speech.isAvailable()) queue.next(candidates,safe)?.let { beginQuestion(it.id) }
                    }
                }
            }
            if(conversation.asking&&now-questionAt>=20_000){speech.cancel();listening=false;conversation.defer();questionAt=now;syncQuestion()}
            if(conversation.stage==EnergyConversation.Stage.FINISHED&&now-questionAt>=5000){conversation.clear();syncQuestion()}
            footer.text=footerMessage(when{
                scene.preview->"미리보기 중이에요.\n친구는 실제 기록에 남지 않아요."
                conversation.asking->if(listening)"답변을 듣고 있어요.\n금액은 확인한 뒤 기록해요." else "음성 연결을 확인하고 있어요.\n응답이 없으면 초안으로 남겨요."
                toastUntil>now->toastText
                pendingCount>0->"확인할 주유·충전 후보 ${pendingCount}건\n정차 후 차량 일지에서 이어갈 수 있어요."
                !state.enabled->"자동 기록이 꺼져 있어요.\n메뉴에서 다시 켤 수 있어요."
                parked&&state.trip?.endedAt!=null->state.trip!!.let { "이번 운행 ${it.distanceKm?.let { d->"%.1fkm".format(d) } ?: "거리 확인 필요"} · 친구 ${it.friends.size}명\n오늘의 드라이브에 남겼어요." }
                else->"${state.recordingStatus}\n만난 친구도 함께 남겨요."
            })
            handler.postDelayed(this,200)
        }
    }
    private fun askedKey(id:String)="companion.asked/${diary.profile().id}/$user/$id"
    fun beginQuestion(candidateId:String):Boolean {
        if(scene.preview||!resumed)return false
        if(!conversation.begin(candidateId,user,state.odometer))return false
        store.saveSetting(askedKey(candidateId),"true");questionAt=SystemClock.elapsedRealtime();speechAttempt++
        syncQuestion();listen();return true
    }
    private fun syncQuestion(){
        scene.question=conversation.text;scene.questionHint=conversation.hint
        scene.questionContext=if(conversation.stage==EnergyConversation.Stage.CONFIRM)"한 번만 확인할게요" else "차곡 · 자동 기록"
        scene.invalidate()
    }
    private fun listen(){
        if(!connectVehicle||!resumed||!conversation.asking||listening||!speech.isAvailable())return
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return
        val token=++speechAttempt;listening=true
        speech.listen(object:SpeechCallbacks{
            override fun onState(state:ListenState){if(token==speechAttempt)listening=state!=ListenState.IDLE}
            override fun onPartial(text:String)=Unit // Do not display live transcription on the driving screen.
            override fun onFinal(text:String){if(token==speechAttempt&&resumed){listening=false;receiveAnswer(text)}}
            override fun onFailed(reason:String){if(token==speechAttempt&&resumed){listening=false;conversation.defer();questionAt=SystemClock.elapsedRealtime();syncQuestion()}}
        })
    }
    fun receiveAnswer(text:String){
        conversation.receive(text);questionAt=SystemClock.elapsedRealtime();syncQuestion()
        if(conversation.asking)handler.postDelayed({listen()},450)
    }
    private fun reloadRecords(){records=store.encounters(diary.profile().id,user);scene.seen=records.map{it.id}.toSet();if(::friendsButton.isInitialized)updateStatus()}
    private fun toggleCollection(){if(showingCollection)hideCollection()else showCollection()}
    private fun hideCollection(){collection?.let{body.removeView(it)};collection=null;scene.visibility=View.VISIBLE;if(resumed&&automaticFrames)scene.resume();updateStatus()}
    private fun showCollection(){
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
        val options=if(scene.preview)arrayOf("다음 만남", "급가속", "왼쪽 / 오른쪽", "비 효과", "미리보기 끝내기")
            else arrayOf("차량 스킨", "일지·차량 관리", "만난 친구들", "미뤄둔 질문 이어가기", "주행 연출 미리보기", "차량·음성 권한", if(store.setting(RecordingStartup.ENABLED)=="false")"자동 기록 켜기" else "자동 기록 종료", if(diary.questionQueue(user).muted())"오늘 질문 다시 받기" else "오늘은 묻지 않기")
        dialog=AlertDialog.Builder(this).setTitle(if(scene.preview)"주행 연출 미리보기"else "차곡").setItems(options){_,index->
            if(!parked)return@setItems
            if(scene.preview){when(index){
                0->{val next=(Encounters.all.indexOf(scene.director.active?.scene)+1)%Encounters.all.size;scene.begin(Encounters.all[next],true)}
                1->{scene.previewSpeed=85f;scene.previewAcceleration=3f;handler.postDelayed({scene.previewAcceleration=0f},2500)}
                2->scene.previewTurn=if(scene.previewTurn<0)1 else -1
                3->scene.previewRain=if(scene.previewRain>.1f)0f else 1f
                4->{scene.preview=false;scene.director.active=null;scene.director.remainingSeconds=180f;scene.director.scenery=Scenery.CITY}
            }}else when(index){
                0->showSkins()
                1->startActivity(Intent(this,MainActivity::class.java))
                2->showCollection()
                3->{conversation.clear();val candidate=diary.pendingCandidates().firstOrNull();if(candidate!=null)beginQuestion(candidate.id)else Toast.makeText(this,"미뤄둔 질문이 없어요",Toast.LENGTH_SHORT).show()}
                4->{speech.cancel();conversation.defer();conversation.clear();syncQuestion();hideCollection();scene.preview=true;scene.previewSpeed=45f;scene.previewAcceleration=0f;scene.begin(Encounters.all.first(),true)}
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
        outState.putBundle("scene",Bundle().apply{
            putBoolean("preview",scene.preview);putString("scenery",scene.director.scenery.name);putFloat("remaining",scene.director.remainingSeconds);putString("last",scene.director.lastId)
            putFloat("travel",scene.travel);putInt("previewTurn",scene.previewTurn);putFloat("previewRain",scene.previewRain);putFloat("previewSpeed",scene.previewSpeed)
            scene.director.active?.let{putString("id",it.scene.id);putString("token",it.token);putFloat("y",it.y);putFloat("seen",it.seenSeconds);putBoolean("recorded",it.recorded)}
        });super.onSaveInstanceState(outState)
    }
    @Deprecated("Android framework back dispatch")
    override fun onBackPressed(){when{showingCollection->hideCollection();scene.preview->{scene.preview=false;scene.director.active=null;updateStatus()};else->super.onBackPressed()}}
}
