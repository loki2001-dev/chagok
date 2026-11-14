package io.chagok.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.chagok.app.companion.VehicleMonitorService
import io.chagok.app.drive.DrivingState

/** Stop the foreground SQLite owner before resetting ONLY the disposable smoke database. */
fun resetSmoke(context: Context) {
    check(context.packageName.endsWith(".smoke")) { "Never reset the production database" }
    context.stopService(Intent(context, VehicleMonitorService::class.java))
    val until=System.currentTimeMillis()+5000
    @Suppress("DEPRECATION")
    while(context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .any { it.service.className==VehicleMonitorService::class.java.name }) {
        check(System.currentTimeMillis()<until){"Foreground monitor did not stop before database isolation"}
        Thread.sleep(50)
    }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    context.deleteDatabase("chagok.db")
    if(InstrumentationRegistry.getArguments().getString("virtualConsole")=="true") {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.sendStatus(2,Bundle().apply{putString("chagok_signal","RESET")})
        var driving:DrivingState?=null
        instrument.runOnMainSync{driving=DrivingState(context){}}
        try {
            val end=System.currentTimeMillis()+12000
            while(true){
                var parked=false
                instrument.runOnMainSync{driving!!.refresh();parked=driving!!.available&&!driving!!.restricted}
                if(parked)break
                check(System.currentTimeMillis()<end){"Virtual Console did not restore Park before test"}
                Thread.sleep(150)
            }
        } finally {instrument.runOnMainSync{driving?.release()}}
    }
}
