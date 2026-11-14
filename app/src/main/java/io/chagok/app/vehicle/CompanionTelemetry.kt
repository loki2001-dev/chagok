package io.chagok.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.chagok.app.companion.MotionSnapshot
import io.chagok.app.companion.SpeedFilter
import io.chagok.app.companion.WiperRainPolicy

/** Read-only, capability-gated adapter. No setters and no navigation/location permission. */
class CompanionTelemetry(private val context: Context, private val log: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var property: CarPropertyManager? = null
    private val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val speed = SpeedFilter()
    private val rain = WiperRainPolicy()
    private var parkGear: Boolean? = null
    private var gearAt = 0L
    private var turn: Int? = null
    private var turnAt = 0L
    private var wiperMode: String? = null
    private var wiperOn: Boolean? = null
    private var modes = emptyList<String>()
    private var wiperReadable = false
    private var turnReadable = false
    private var turnRegistered = false
    private var running = false
    private var generation = 0
    private var wiperRequest = 0
    private fun acceptTurn(value: TurnSignalValue?) {
        turn = when (value?.name) { "LEFT" -> -1; "RIGHT" -> 1; "OFF" -> 0; else -> null }
        turnAt = SystemClock.elapsedRealtime()
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = onMain { acceptTurn(state) }
        override fun onFailed(e: Exception) = onMain { turn = null; turnReadable = false }
    }
    private val speedListener = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = onMain {
            speed.accept(SystemClock.elapsedRealtime(), (value.value as? Float)?.takeIf { value.status == CarPropertyValue.STATUS_AVAILABLE })
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) = onMain { speed.accept(SystemClock.elapsedRealtime(), null) }
    }
    private fun onMain(block: () -> Unit) {
        val token = generation
        main.post { if (running && token == generation) block() }
    }
    fun start() {
        if (running) return
        running = true; generation++
        connect(); main.post(poll)
    }
    private fun connect() {
        if (vehicle == null) runCatching { Vehicle(context.applicationContext).also { it.initialize() } }
            .onSuccess { vehicle = it }.onFailure { log("companion vehicle unavailable: ${it.javaClass.simpleName}") }
        if (car?.isConnected != true || property == null) runCatching {
            runCatching { property?.unregisterCallback(speedListener); car?.disconnect() }
            car = Car.createCar(context.applicationContext)
            property = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            property?.registerCallback(speedListener, VehiclePropertyIds.PERF_VEHICLE_SPEED, 5f)
        }.onFailure { property = null; log("companion speed unavailable: ${it.javaClass.simpleName}") }
        val v = vehicle ?: return
        if (!turnReadable) runCatching { v.getTurnSignal().checkTurnSignalStateCapabilities({ config -> onMain {
            turnReadable = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
            if (turnReadable && !turnRegistered) runCatching { v.getTurnSignal().registerTurnSignalState(turnListener) }
                .onSuccess { turnRegistered = true }
        } }, { onMain { turnReadable = false } }) }
        if (!wiperReadable) runCatching { v.getWiper().checkWiperSwitchCapabilities({ config, areas -> onMain {
            modes = areas.entries.firstOrNull { WindowValue.FRONT_WINDSHIELD in it.key.values }?.value?.map { it.name }.orEmpty()
            wiperReadable = modes.isNotEmpty() && (config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE)
        } }, { onMain { wiperReadable = false } }) }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            connect()
            runCatching {
                val value = property?.getProperty(java.lang.Float::class.java, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
                speed.accept(SystemClock.elapsedRealtime(), value?.value?.toFloat()?.takeIf { value.status == CarPropertyValue.STATUS_AVAILABLE })
            }.onFailure { speed.accept(SystemClock.elapsedRealtime(), null) }
            if (turnReadable) runCatching { vehicle?.getTurnSignal()?.getCurrentTurnSignalState({ value -> onMain {
                acceptTurn(value)
            } }, { onMain { turn = null; turnReadable = false } }) }.onFailure { turn = null }
            runCatching {
                val gear=property?.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION,0)
                parkGear=if(gear?.status==CarPropertyValue.STATUS_AVAILABLE&&gear.value!=android.car.VehicleGear.GEAR_UNKNOWN)gear.value==android.car.VehicleGear.GEAR_PARK else null
                gearAt=SystemClock.elapsedRealtime()
            }.onFailure { parkGear=null }
            readWiper()
            main.postDelayed(this, 2000)
        }
    }
    private fun readWiper() {
        val request = ++wiperRequest
        if (!wiperReadable) { wiperMode = null; wiperOn = null; rain.sample(SystemClock.elapsedRealtime(), null, null, modes); return }
        var mode: String? = null; var on: Boolean? = null; var finished = 0
        fun accept() {
            if (request != wiperRequest || !running) return
            if (++finished < 2) return
            wiperMode = mode; wiperOn = on
            rain.sample(SystemClock.elapsedRealtime(), mode, on, modes)
        }
        runCatching {
            vehicle?.getWiper()?.getCurrentWiperSwitch(front,
                { value -> onMain { mode = value?.name; accept() } }, { onMain { mode = null; wiperReadable = false; accept() } })
            vehicle?.getWiper()?.getCurrentWiperState(front,
                { value -> onMain { on = value?.name?.let { it == "ON" }; accept() } }, { onMain { on = null; accept() } })
        }.onFailure { rain.sample(SystemClock.elapsedRealtime(), null, null, modes) }
        main.postDelayed({ if (running && request == wiperRequest && finished < 2) {
            wiperMode = null; wiperOn = null; rain.sample(SystemClock.elapsedRealtime(), null, null, modes)
        } }, 1800)
    }
    fun snapshot(now: Long = SystemClock.elapsedRealtime()) = MotionSnapshot(speed.speed(now), speed.acceleration(now),
        turn.takeIf { now - turnAt in 0..5000 }, rain.tick(now), wiperMode, wiperOn, rain.confirmed,parkGear=parkGear.takeIf { now-gearAt in 0..5000 })

    fun close() {
        running = false; generation++; wiperRequest++; main.removeCallbacksAndMessages(null)
        runCatching { property?.unregisterCallback(speedListener) }; runCatching { car?.disconnect() }
        if (turnRegistered) runCatching { vehicle?.getTurnSignal()?.unregisterTurnSignalState(turnListener) }
        turnRegistered = false
        runCatching { vehicle?.release() }; vehicle = null; property = null; car = null
    }
}
