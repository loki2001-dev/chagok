package io.chagok.app.companion

/** Isolated view tests step frames explicitly; the live SDK probe uses the real frame loop. */
class CompanionHarnessActivity : CompanionActivity() {
    override val connectVehicle = false
    override val automaticFrames = false
    fun inject(motion: MotionSnapshot) = applyMonitorState(MonitorState(motion = motion))
    fun inject(state: MonitorState) = applyMonitorState(state)
    fun openCollection() = showCollection()
}

/** First-run UI uses deterministic Park state without requesting permissions or starting services. */
class FirstRunHarnessActivity : CompanionActivity() {
    override val connectVehicle = false
    override val automaticFrames = false
    var safelyParked = true
    override val setupParked get() = safelyParked
    fun inject(state: MonitorState) = applyMonitorState(state)
}

/** Deterministic permission/lifecycle regression paths, isolated from the real permission UI. */
class PermissionReviewHarnessActivity : CompanionActivity() {
    override val connectVehicle = false
    override val automaticFrames = false
    override val automaticQuestions = false
    var speedPermission = true
    var microphonePermission = false
    var requestedPermissions = emptyList<String>()
    var settingsOpened = false
    override val vehicleSpeedGranted get() = speedPermission
    override fun hasAppPermission(permission: String) = when(permission) {
        android.Manifest.permission.RECORD_AUDIO -> microphonePermission
        "android.car.permission.CAR_SPEED" -> speedPermission
        else -> super.hasAppPermission(permission)
    }
    override fun requestAppPermissions(permissions: Array<String>, requestCode: Int) { requestedPermissions=permissions.toList() }
    override fun openPermissionSettings() { settingsOpened=true }
    fun inject(state: MonitorState) = applyMonitorState(state)
    fun drivingParked(value: Boolean) = applyDrivingSafety(value)
    fun openCollection() = showCollection()
    fun askWithPermission(id: String) = requestVoiceQuestion(id)
    fun microphoneResult(granted: Boolean) {
        microphonePermission=granted
        onRequestPermissionsResult(1043,arrayOf(android.Manifest.permission.RECORD_AUDIO),
            intArrayOf(if(granted)android.content.pm.PackageManager.PERMISSION_GRANTED else android.content.pm.PackageManager.PERMISSION_DENIED))
    }
}

/** Opaque local destination for testing a real background lifecycle transition. */
class CompanionPauseTargetActivity : android.app.Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        setContentView(android.widget.TextView(this).apply { text="화면 전환 테스트";setBackgroundColor(android.graphics.Color.WHITE) })
    }
}
