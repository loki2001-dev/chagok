package io.chagok.app.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.chagok.app.data.SqliteStore

/** User choice survives app/process/vehicle restarts. Force-stop remains under OS control. */
object RecordingStartup {
    const val ENABLED=io.chagok.app.domain.FirstRunSetup.RECORDING
    fun start(context: Context): Boolean {
        val store=SqliteStore(context)
        return try {
            if(store.setting(ENABLED)!="true")false
            else try {
                context.startForegroundService(Intent(context,VehicleMonitorService::class.java))
                store.saveSetting("monitor.startError","");true
            } catch(e: IllegalStateException) {
                store.saveSetting("monitor.startError","앱을 열어 자동 기록을 다시 연결해 주세요");false
            } catch(e: SecurityException) {
                store.saveSetting("monitor.startError","차량 권한을 확인해 주세요");false
            }
        } finally { store.close() }
    }
}

class RecordingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) {
            val store=SqliteStore(context)
            try { store.saveSetting("monitor.lastStartEvent","${intent.action}|${java.time.Instant.now()}") } finally { store.close() }
            RecordingStartup.start(context)
        }
    }
}
