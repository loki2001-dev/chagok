package io.chagok.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import io.chagok.app.companion.MonitorConnection
import io.chagok.app.data.*
import io.chagok.app.domain.*
import io.chagok.app.vehicle.PleosLocation
import java.util.concurrent.Executors

/** Parked-only catalogue setup and explicit visit review. No coordinates leave the vehicle. */
class PlacesActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var store: SqliteStore
    private lateinit var diary: DiaryService
    private lateinit var monitor: MonitorConnection
    private lateinit var body: LinearLayout
    private lateinit var blocked: TextView
    private lateinit var status: TextView
    private var safe = false
    private var dialog: AlertDialog? = null
    private var user = "나"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Palette.configure(resources.configuration); ui = Ui(this)
        store = SqliteStore(this); diary = DiaryService(store)
        user = store.setting("user") ?: "나"; diary.selectUser(user)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24),ui.dp(20),ui.dp(24),ui.dp(20)); setBackgroundColor(Palette.bg) }
        blocked = ui.text("자동 기록을 켜고 P단으로 정차한 뒤 이용할 수 있어요", 24f)
        root.addView(blocked)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }); setContentView(root)
        monitor = MonitorConnection(this) { state ->
            safe = !state.motion.restricted && state.motion.parkGear == true && state.motion.speedMps?.let { kotlin.math.abs(it) < .3f } == true
            body.visibility = if(safe) View.VISIBLE else View.GONE
            blocked.visibility = if(safe) View.GONE else View.VISIBLE
            if(!safe) dialog?.dismiss()
            if(::status.isInitialized) status.text = summary()
        }
        render(); body.visibility = View.GONE
    }
    override fun onResume() { super.onResume(); user = store.setting("user") ?: "나"; diary.selectUser(user); monitor.open(); render() }
    override fun onPause() { safe = false; dialog?.dismiss(); monitor.close(); body.visibility = View.GONE; super.onPause() }
    override fun onDestroy() { worker.shutdown(); store.close(); super.onDestroy() }
    private fun text(value: String) = ui.text(value, 21f).apply { setPadding(0,ui.dp(12),0,ui.dp(12)); body.addView(this) }
    private fun button(value: String, action: () -> Unit) { body.addView(ui.button(value) { if(safe) action() }) }
    private fun summary() = "목록 ${store.places().size}곳 · 버전 ${store.setting("places.version") ?: "없음"}\n" +
        "원본 발행: ${store.setting("places.publishedAt") ?: "없음"}\n마지막 확인: ${store.setting("places.checkedAt") ?: "없음"}\n" +
        (store.setting("places.status") ?: "방문 감지 꺼짐")
    private fun render() {
        body.removeAllViews()
        text("정비소·주유소 방문 감지")
        status = text(summary())
        text("위치는 차량 안에서 목록과 비교합니다. 근처 정차는 이용 사실을 뜻하지 않으며, 확인 후에만 일지에 저장합니다.")
        val enabled = store.setting(PlaceVisitTracker.ENABLED) == "true"
        button(if(enabled) "방문 감지 끄기" else "방문 감지 켜기") {
            if(enabled) { store.saveSetting(PlaceVisitTracker.ENABLED,"false"); render() }
            else if(checkSelfPermission(PleosLocation.PERMISSION) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(PleosLocation.PERMISSION), 71)
            else { store.saveSetting(PlaceVisitTracker.ENABLED,"true"); CatalogueDownloads.check(this); render() }
        }
        text("HTTPS 목록 주소 · 최초 다운로드 후 하루 한 번 변경 여부를 확인해요")
        val url = ui.input("https://…/places.json", store.setting(CatalogueSync.URL), false).apply { setSingleLine() }
        body.addView(url)
        button("주소 저장하고 목록 확인") {
            runCatching { PlaceCatalogue.validateUrl(url.text.toString()) }.onSuccess { value ->
                store.saveSetting(CatalogueSync.URL, value)
                CatalogueDownloads.check(this, true) { message -> if(!isDestroyed) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); render() } }
            }.onFailure { Toast.makeText(this,it.message,Toast.LENGTH_LONG).show() }
        }
        button("자동 다운로드 주소 해제") { store.saveSetting(CatalogueSync.URL, ""); render() }
        button("목록 파일 가져오기 (JSON)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 72)
        }
        text("파일을 가져오면 자동 다운로드 주소는 해제됩니다. CSV 원본은 저장소의 변환 도구로 목록 파일을 만들 수 있어요.")
        text("확인할 방문")
        button("방문 후보 새로고침") { render() }
        val visits = diary.pendingVisits(user)
        if(visits.isEmpty()) text("새 방문 후보가 없어요. 작성 중인 기록은 차량 일지의 ‘확인할 기록’에서 이어가세요.")
        visits.forEach { visit ->
            text("${visit.place.name} · ${visit.place.kind.label}\n${visit.place.address}\n${visit.arrivedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()}" +
                if(visit.hasGap) "\n관측 공백이 있어 실제 체류 시간은 알 수 없어요" else "\n근처 정차 감지 · 실제 방문 여부를 확인해 주세요")
            button(if(visit.place.kind == PlaceKind.FUEL) "주유했어요 · 일지 작성" else "정비받았어요 · 내역 선택") {
                val items = Item.entries.filter { it.availableFor(diary.profile().powertrain) &&
                    if(visit.place.kind == PlaceKind.FUEL) it == Item.REFUEL else it != Item.REFUEL && it != Item.CHARGE }
                fun draft(item: Item) {
                    if(!safe) return
                    runCatching { diary.visitDraft(visit.id, user, item) }.onSuccess { entry ->
                        startActivity(Intent(this, MainActivity::class.java).putExtra("visitDraft",entry.id)); finish()
                    }.onFailure { Toast.makeText(this,it.message,Toast.LENGTH_LONG).show(); render() }
                }
                if(items.size == 1) draft(items.first())
                else if(items.isNotEmpty()) dialog = AlertDialog.Builder(this).setTitle("어떤 정비를 받으셨나요?")
                    .setItems(items.map { it.label }.toTypedArray()) { _, index -> draft(items[index]) }.show()
                else Toast.makeText(this,"차량 동력원 설정을 확인해 주세요",Toast.LENGTH_LONG).show()
            }
            button("이용하지 않았어요") { diary.rejectVisit(visit.id,user); render() }
        }
        button("차량 일지로 돌아가기") { finish() }
    }
    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if(request == 71) {
            store.saveSetting(PlaceVisitTracker.ENABLED, (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED).toString())
            render()
        }
    }
    @Deprecated("Activity result API retained to match this native Activity app")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request,result,data)
        val uri = data?.data ?: return
        if(request != 72 || result != RESULT_OK) return
        // The user selected this file while parked. Both parsing and bulk installation stay off the UI thread.
        val revision = store.setting("places.revision")
        val sourceUrl = store.setting(CatalogueSync.URL)
        val app = applicationContext
        worker.execute {
            val parsed = runCatching {
                val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while(true) { val n=input.read(buffer); if(n<0) break
                        require(output.size()+n <= PlaceCatalogue.MAX_BYTES); output.write(buffer,0,n) }
                    output.toByteArray()
                }
                val catalogue = PlaceCatalogue.parse(bytes)
                val destination = SqliteStore(app)
                try { destination.transaction {
                    check(destination.setting("places.revision") == revision && destination.setting(CatalogueSync.URL) == sourceUrl)
                    CatalogueSync(destination).install(catalogue,"file")
                    destination.saveSetting(CatalogueSync.URL, ""); destination.saveSetting("places.checkedAt", "")
                } } finally { destination.close() }
            }
            main.post {
                if(isDestroyed || isFinishing) return@post
                parsed.onSuccess {
                    render()
                }.onFailure { Toast.makeText(this,"목록 형식 또는 파일 크기를 확인해 주세요",Toast.LENGTH_LONG).show() }
            }
        }
    }
}
