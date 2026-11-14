package io.chagok.app.companion

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import java.time.Instant
import java.time.Duration
import java.util.UUID
import io.chagok.app.R
import io.chagok.app.data.SqliteStore
import io.chagok.app.domain.*
import io.chagok.app.drive.DrivingState
import io.chagok.app.vehicle.CompanionTelemetry
import io.chagok.app.vehicle.PleosVehicle

data class MonitorState(val motion: MotionSnapshot = MotionSnapshot(), val kind: EnergyKind? = null,
    val energy: Double? = null, val energyStatus: String = "차량 연결 중", val odometer: Reading = Reading.Unavailable("아직 읽지 않음"),
    val recordingStatus: String = "기록 연결 확인 중", val enabled: Boolean = true, val trip: DriveTrip? = null)

/** The single energy tracker survives UI/background transitions. Only local vehicle data is retained. */
class VehicleMonitorService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@VehicleMonitorService }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: SqliteStore
    private lateinit var diary: DiaryService
    private lateinit var vehicle: PleosVehicle
    private lateinit var telemetry: CompanionTelemetry
    private lateinit var driving: DrivingState
    private val observers = linkedSetOf<(MonitorState) -> Unit>()
    var state = MonitorState(); private set
    private var closed = false
    private var lastEnergy = -5000L
    private var lastOdometer = -30000L
    private var generation = 0
    private lateinit var trips: TripRecorder
    private var lastTripSample = -1000L
    private var lastRecovery = 0L
    private var notificationStatus = ""
    private var gapStart: Instant? = null
    private var lastHealthy: Instant? = null
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("vehicle-monitor", "자동 차량일지", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0, Intent(this, CompanionActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel.id).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("차곡 · 자동 기록 중").setContentText("차량 안에서 주유·충전 후보를 모으고 있어요")
            .setContentIntent(open).setOngoing(true).setShowWhen(false).build()
        startForeground(1042, notification)
        store = SqliteStore(this); diary = DiaryService(store)
        trips=TripRecorder(store)
        lastRecovery=SystemClock.elapsedRealtime()
        lastHealthy=store.setting("monitor.lastHealthy")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        gapStart=store.setting("monitor.gapStart")?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if(store.setting(RecordingStartup.ENABLED)!="true") { stopSelf();return }
        vehicle = PleosVehicle(this, ::log); vehicle.start()
        telemetry = CompanionTelemetry(this, ::log); telemetry.start()
        driving = DrivingState(this, ::log)
        main.post(tick)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(store.setting(RecordingStartup.ENABLED)!="true") { stopSelf();return START_NOT_STICKY }
        return START_STICKY
    }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    fun observe(callback: (MonitorState) -> Unit) { observers += callback; callback(state) }
    fun removeObserver(callback: (MonitorState) -> Unit) { observers -= callback }
    private fun emit() { observers.toList().forEach { it(state) } }
    private fun log(message: String) = Log.i("Chagok", message)
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            if(store.setting(RecordingStartup.ENABLED)!="true") { trips.stop(Instant.now());stopSelf();return }
            val now = SystemClock.elapsedRealtime()
            val desired = diary.energyKinds().first()
            if (state.kind != desired) {
                generation++
                state = state.copy(kind = desired, energy = null, energyStatus = "확인 중")
                lastEnergy = -5000
            }
            if (now - lastEnergy >= 5000) {
                driving.refresh(); lastEnergy = now
                val token = generation
                vehicle.readEnergy(desired) { value ->
                    if (!closed && token == generation) {
                        state = state.copy(energy = value, energyStatus = if (value == null) "차량 값 미수신" else "${desired.name} 수신 중")
                        // A single tracker owns baseline/candidate writes; UI activities only observe.
                        val motion=telemetry.snapshot()
                        diary.observeEnergy(desired, if(motion.parkGear==null)null else value,
                            parked=driving.available&&!driving.restricted&&motion.parkGear==true)
                        emit()
                    }
                }
            }
            if (now - lastOdometer >= 30000) {
                lastOdometer = now
                vehicle.readOdometer { if (!closed) { state = state.copy(odometer = it); emit() } }
            }
            state = state.copy(motion = telemetry.snapshot(now).copy(restricted = driving.restricted, animationAllowed = driving.animationAllowed))
            if(now-lastTripSample>=1000) {
                lastTripSample=now
                val at=Instant.now()
                val healthy=driving.available&&state.motion.speedMps!=null
                val parked=if(healthy)state.motion.parkGear?.let { it&&!driving.restricted&&kotlin.math.abs(state.motion.speedMps!!)<.3f } else null
                if(parked!=null) {
                    val old=store.setting("monitor.parked")
                    if(parked&&old!="true")store.saveSetting("monitor.parkSequence",((store.setting("monitor.parkSequence")?.toLongOrNull() ?: 0)+1).toString())
                    if(old!=parked.toString())store.saveSetting("monitor.parked",parked.toString())
                }
                if(healthy) {
                    if(gapStart==null&&lastHealthy!=null&&Duration.between(lastHealthy,at).seconds>10)gapStart=lastHealthy
                    gapStart?.let { from -> if(at>from)store.saveGap(RecordingGap(UUID.randomUUID().toString(),diary.profile().id,from,at,"차량 연결·앱 실행 공백")) }
                    if(gapStart!=null) { gapStart=null;store.saveSetting("monitor.gapStart","") }
                    if(lastHealthy==null||Duration.between(lastHealthy,at).seconds>=5) { lastHealthy=at;store.saveSetting("monitor.lastHealthy",at.toString()) }
                } else if(gapStart==null) { gapStart=lastHealthy ?: at;store.saveSetting("monitor.gapStart",gapStart.toString()) }
                val trip=trips.sample(diary.profile().id,store.setting("user") ?: "나",at,state.motion.speedMps,parked,(state.odometer as? Reading.Value)?.observation)
                val kmObservation=(state.odometer as? Reading.Value)?.observation
                val distanceAvailable=kmObservation!=null&&Duration.between(kmObservation.receivedAt,at).seconds in 0..45
                val label=if(!healthy)"기록 연결 확인 필요" else if(state.motion.parkGear==null)"운행 기록 중 · 정차 신호 확인" else if(!distanceAvailable)"운행 기록 중 · 거리 연결 확인" else if(state.energy==null)"운행 기록 중 · 잔량 연결 확인" else "자동 기록 중"
                state=state.copy(recordingStatus=label,trip=trip ?: state.trip)
                if(label!=notificationStatus) {
                    notificationStatus=label
                    getSystemService(NotificationManager::class.java).notify(1042,Notification.Builder(this@VehicleMonitorService,"vehicle-monitor")
                        .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("차곡 · $label")
                        .setContentText(if(healthy)"운행과 주유·충전 후보를 차량 안에 보관해요" else "앱에서 차량 권한과 연결을 확인해 주세요")
                        .setContentIntent(PendingIntent.getActivity(this@VehicleMonitorService,0,Intent(this@VehicleMonitorService,CompanionActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                        .setOngoing(true).setShowWhen(false).build())
                }
                if((!healthy||state.energy==null)&&now-lastRecovery>30000) {
                    lastRecovery=now;vehicle.stop();vehicle.start();lastEnergy=-5000;lastOdometer=-30000
                    driving.release();driving=DrivingState(this@VehicleMonitorService,::log)
                }
            }
            emit(); main.postDelayed(this, 200)
        }
    }
    override fun onDestroy() {
        closed = true; generation++; main.removeCallbacksAndMessages(null); observers.clear()
        if(::telemetry.isInitialized)telemetry.close()
        if(::vehicle.isInitialized)vehicle.stop()
        if(::driving.isInitialized)driving.release()
        if(::store.isInitialized) {
            if(store.setting(RecordingStartup.ENABLED)=="false"&&::trips.isInitialized)trips.stop(Instant.now())
            store.close()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

/** Binding follows each Activity; the foreground monitor keeps collecting when navigation takes focus. */
class MonitorConnection(private val context: Context, private val update: (MonitorState) -> Unit) : ServiceConnection {
    private var service: VehicleMonitorService? = null
    private var bound = false
    fun open() {
        if (bound) return
        val intent = Intent(context, VehicleMonitorService::class.java)
        if(!RecordingStartup.start(context,firstUse=true)) {
            val store=SqliteStore(context)
            try { update(MonitorState(enabled=store.setting(RecordingStartup.ENABLED)=="true",recordingStatus=store.setting("monitor.startError")?.takeIf { it.isNotBlank() } ?: "자동 기록 꺼짐")) } finally { store.close() }
            return
        }
        bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }
    fun close() {
        service?.removeObserver(update); service = null
        if (bound) { context.unbindService(this); bound = false }
    }
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        service = (binder as VehicleMonitorService.LocalBinder).service
        service?.observe(update)
    }
    override fun onServiceDisconnected(name: ComponentName) { service = null; update(MonitorState(energyStatus = "차량 연결이 끊겼어요",recordingStatus="기록 연결 확인 필요")) }
}
