package io.chagok.app.companion

/** Isolated view tests step frames explicitly; the live SDK probe uses the real frame loop. */
class CompanionHarnessActivity : CompanionActivity() {
    override val connectVehicle = false
    override val automaticFrames = false
    fun inject(motion: MotionSnapshot) = applyMonitorState(MonitorState(motion = motion))
    fun inject(state: MonitorState) = applyMonitorState(state)
}

/** Opaque local destination for testing a real background lifecycle transition. */
class CompanionPauseTargetActivity : android.app.Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        setContentView(android.widget.TextView(this).apply { text="화면 전환 테스트";setBackgroundColor(android.graphics.Color.WHITE) })
    }
}
