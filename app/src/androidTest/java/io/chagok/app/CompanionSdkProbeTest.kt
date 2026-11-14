package io.chagok.app

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in read-only audit. A completed probe is NOT a supported-feature assertion. */
class CompanionSdkProbeTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val events = CopyOnWriteArrayList<JSONObject>()
    private fun record(kind: String, value: Any?) {
        events.add(JSONObject().put("kind", kind).put("elapsedMs", SystemClock.elapsedRealtime())
            .put("value", value ?: JSONObject.NULL))
    }
    private fun attempt(kind: String, block: () -> Unit) {
        runCatching(block).onFailure { record("$kind.error", "${it.javaClass.simpleName}: ${it.message}") }
    }
    private fun phase(name: String) {
        record("phase", name)
        instrument.sendStatus(0, Bundle().apply { putString("sdk_probe_phase", name) })
    }

    @Test fun auditWiperSwitchLevels() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sdkProbe") == "true")
        check(context.packageName.endsWith(".smoke"))
        val vehicle = Vehicle(context)
        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        record("permission.CAR_WINDOWS", context.checkSelfPermission("pleos.car.permission.CAR_WINDOWS") == 0)
        try {
            instrument.runOnMainSync { vehicle.initialize() }
            Thread.sleep(2000)
            attempt("wiper.switch.capability") { vehicle.getWiper().checkWiperSwitchCapabilities(
                { config: CapabilityConfig, areas: Map<WindowArea, List<WiperSwitch>> ->
                    val supported = JSONObject()
                    areas.forEach { (area, modes) ->
                        supported.put(area.values.joinToString("+") { it.name }, JSONArray(modes.map { it.name }))
                    }
                    record("wiper.switch.capability", JSONObject().put("access", config.accessType.name).put("areas", supported))
                }, { record("wiper.switch.capability.error", it.toString()) }) }
            for (name in listOf("WIPER_OFF", "WIPER_LOW", "WIPER_HIGH", "WIPER_AUTO", "WIPER_END")) {
                phase(name)
                // Two reads per selected mode confirm a persistent value, not a transient callback.
                repeat(2) {
                    Thread.sleep(1500)
                    attempt("wiper.switch.read") { vehicle.getWiper().getCurrentWiperSwitch(front,
                        { record("wiper.switch.read", JSONObject().put("phase", name).put("mode", it?.name ?: JSONObject.NULL)) },
                        { record("wiper.switch.read.error", it.toString()) }) }
                    attempt("wiper.state.read") { vehicle.getWiper().getCurrentWiperState(front,
                        { record("wiper.state.read", JSONObject().put("phase", name).put("state", it?.name ?: JSONObject.NULL)) },
                        { record("wiper.state.read.error", it.toString()) }) }
                }
            }
            Thread.sleep(500)
        } finally {
            instrument.runOnMainSync { vehicle.release() }
            val report = JSONObject().put("package", context.packageName)
                .put("device", android.os.Build.FINGERPRINT).put("events", JSONArray(events.toList()))
            File(context.filesDir, "wiper-sdk-probe.json").writeText(report.toString(2))
            instrument.sendStatus(0, Bundle().apply { putString("sdk_probe_report", "wiper-sdk-probe.json") })
        }
    }

    @Test fun auditReadOnlyCapabilities() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sdkProbe") == "true")
        check(context.packageName.endsWith(".smoke"))
        listOf("android.car.permission.CAR_SPEED", "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.CAR_WINDOWS", "pleos.car.permission.NAVI_ROUTE").forEach { permission ->
            record("permission", JSONObject().put("name", permission)
                .put("granted", context.checkSelfPermission(permission) == 0)
                .put("protectionLevel", runCatching { context.packageManager.getPermissionInfo(permission, 0).protectionLevel }.getOrNull()))
        }
        val vehicle = Vehicle(context)
        val navi = NaviHelper(context)
        var car: Car? = null
        var property: CarPropertyManager? = null
        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val turnListener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = record("turn.event", state?.name)
            override fun onFailed(e: Exception) = record("turn.event.error", e.toString())
        }
        val wiperListener = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = record("wiper.event", state?.name)
            override fun onFailed(e: Exception) = record("wiper.event.error", e.toString())
        }
        val speedListener = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                record("speed.event", JSONObject().put("mps", value.value).put("status", value.status)
                    .put("timestampNanos", value.timestamp))
            }
            override fun onErrorEvent(propertyId: Int, areaId: Int) = record("speed.event.error", "$propertyId/$areaId")
        }
        val naviListener = object : NaviHelperEventListener {
            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
                // Availability only: do not store precise locations or addresses.
                record("navi.location", JSONObject().put("hasCoordinates", currentLocationInfo.latitude.isFinite() && currentLocationInfo.longitude.isFinite())
                    .put("hasAddress", currentLocationInfo.address.isNotBlank()))
            }
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = record("navi.route", routeStateInfo.isRouting)
            override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = record("navi.tbt", tbtInfoList.map { it.type.name }.toString())
        }
        fun readValues() {
            attempt("turn.read") { vehicle.getTurnSignal().getCurrentTurnSignalState(
                { record("turn.read", it?.name) }, { record("turn.read.error", it.toString()) }) }
            attempt("wiper.read") { vehicle.getWiper().getCurrentWiperState(front,
                { record("wiper.read", it?.name) }, { record("wiper.read.error", it.toString()) }) }
            attempt("speed.read") { record("speed.read", property?.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)) }
        }
        try {
            instrument.runOnMainSync {
                attempt("vehicle.initialize") { vehicle.initialize() }
                attempt("speed.initialize") {
                    car = Car.createCar(context)
                    property = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                    record("speed.config", property?.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED)?.toString())
                    record("speed.subscription", property?.registerCallback(speedListener, VehiclePropertyIds.PERF_VEHICLE_SPEED, 5f))
                }
                attempt("navi.initialize") {
                    navi.addListener(naviListener)
                    navi.initialize(onConnected = {
                        record("navi.connected", true)
                        attempt("navi.request") { navi.getCurrentLocationInfo(); navi.getRouteStateInfo(); navi.getTBTInfo() }
                    }, onFailed = { record("navi.connection.error", it.toString()) })
                }
            }
            Thread.sleep(2000)
            attempt("turn.capability") { vehicle.getTurnSignal().checkTurnSignalStateCapabilities(
                { record("turn.capability", it.accessType.name) }, { record("turn.capability.error", it.toString()) }) }
            attempt("wiper.capability") { vehicle.getWiper().checkWiperStateCapabilities(
                { config: CapabilityConfig, areas: Map<WindowArea, List<WiperState>> ->
                    record("wiper.capability", JSONObject().put("access", config.accessType.name).put("areas", areas.toString()))
                }, { record("wiper.capability.error", it.toString()) }) }
            attempt("turn.subscribe") { vehicle.getTurnSignal().registerTurnSignalState(turnListener) }
            attempt("wiper.subscribe") { vehicle.getWiper().registerWiperState(front, wiperListener) }
            for (name in listOf("BASELINE", "LEFT_MOVE_WIPER_ON", "RIGHT", "STOP_WIPER_OFF")) {
                phase(name)
                Thread.sleep(4000)
                readValues()
            }
            Thread.sleep(1000)
        } finally {
            instrument.runOnMainSync {
                attempt("turn.unsubscribe") { vehicle.getTurnSignal().unregisterTurnSignalState(turnListener) }
                attempt("wiper.unsubscribe") { vehicle.getWiper().unregisterWiperState(front, wiperListener) }
                attempt("vehicle.release") { vehicle.release() }
                attempt("navi.release") { navi.removeListener(naviListener); navi.release() }
                attempt("speed.release") { property?.unregisterCallback(speedListener); car?.disconnect() }
            }
            val report = JSONObject().put("package", context.packageName)
                .put("device", android.os.Build.FINGERPRINT).put("events", JSONArray(events.toList()))
            File(context.filesDir, "companion-sdk-probe.json").writeText(report.toString(2))
            instrument.sendStatus(0, Bundle().apply { putString("sdk_probe_report", "companion-sdk-probe.json") })
        }
    }
}
