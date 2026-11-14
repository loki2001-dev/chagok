package io.chagok.app.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import io.chagok.app.domain.PlaceFix
import java.time.Instant

/** SDK observation time is preserved: polling a cached fix never adds dwell time. */
class PleosLocation(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var sdk: FusedLocation? = null
    private var generation = 0
    private var listener: FusedLocationListener? = null
    var fix: PlaceFix? = null; private set
    var status = "위치 감지 꺼짐"; private set
    fun poll(enabled: Boolean) {
        if (!enabled || context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            close(); status = if(enabled) "위치 권한 필요" else "위치 감지 꺼짐"; return
        }
        try {
            if(sdk == null) {
                val client = FusedLocation(context)
                if(!client.isSupported()) { status = "이 플랫폼에서 Pleos 위치 서비스를 사용할 수 없어요"; return }
                val token = ++generation
                listener = object : FusedLocationListener {
                    override fun onChanged(location: LocationData?) { main.post {
                        if(token == generation) {
                            fix = location?.let { PlaceFix(it.latitude, it.longitude, Instant.ofEpochMilli(it.timestampMillis)) }
                            status = if(fix?.fresh(Instant.now()) == true) "위치 수신 중" else "새 위치 대기"
                        }
                    } }
                    override fun onError(exception: Exception) { main.post {
                        if(token == generation) { fix = null; status = "위치 서비스 연결·공유 설정 확인 필요" }
                    } }
                }
                sdk = client; client.initialize(); client.registerFusedLocationCallback(listener!!)
            }
            sdk?.getLastKnownLocation(listener!!)
        } catch (_: Exception) { close(); status = "위치 연결 확인 필요" }
    }
    fun close() {
        generation++
        listener?.let { runCatching { sdk?.unregisterFusedLocationCallback(it) } }
        runCatching { sdk?.release() }; sdk = null; listener = null; fix = null
    }
    companion object { const val PERMISSION = "pleos.car.permission.FUSED_LOCATION" }
}
