package io.chagok.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.chagok.app.domain.EnergyKind
import io.chagok.app.domain.Observation
import io.chagok.app.domain.Reading
import java.time.Instant

/**
 * Pleos Vehicle SDK 어댑터 (기획서 8장 채택 API만 사용).
 * - 누적 주행거리: Odometer.getCurrentDistanceDriven (CAR_DRIVE)
 * - 연료 잔량 / SoC: Fuel.registerFuelPercent, EvBattery.registerEvBatteryStateOfCharge (CAR_ENERGY)
 *
 * 호출 전에 capability로 READ 가능 여부를 확인한다. 결과는 IPC 비동기라 메인 스레드로 옮겨 전달한다.
 * null·실패·시간 초과는 [Reading.Unavailable]이며 0으로 채우지 않는다.
 */
class PleosVehicle(context: Context, private val log: (String) -> Unit) {
    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private val fuelListeners = mutableListOf<FuelPercentListener>()
    private val socListeners = mutableListOf<EvBatteryStateOfChargeListener>()

    fun start(): Boolean {
        if (started) return true
        return runCatching { vehicle.initialize() }
            .onFailure { log("vehicle initialize failed: ${it.message}") }
            .map { started = true; log("vehicle initialized") }
            .isSuccess
    }

    fun stop() {
        if (!started) return
        fuelListeners.forEach { l -> runCatching { vehicle.getFuel().unregisterFuelPercent(l) } }
        socListeners.forEach { l -> runCatching { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(l) } }
        fuelListeners.clear()
        socListeners.clear()
        main.removeCallbacksAndMessages(null)
        runCatching { vehicle.release() }
        started = false
    }

    /** 누적 주행거리 1회 조회. 서비스 연결 직후 실패하면 한 번 다시 시도한다 */
    fun readOdometer(callback: (Reading) -> Unit) = readOdometer(callback, retry = true)

    private fun readOdometer(callback: (Reading) -> Unit, retry: Boolean) {
        if (!started) return callback(Reading.Unavailable("차량 서비스 미연결"))
        val once = Once(callback)
        main.postDelayed({ once(Reading.Unavailable("응답 시간 초과")) }, TIMEOUT_MS)
        val odometer = runCatching { vehicle.getOdometer() }.getOrElse {
            return once(Reading.Unavailable("Odometer 없음: ${it.message}"))
        }
        odometer.checkCurrentDistanceDrivenCapabilities(
            { config: CapabilityConfig ->
                if (!readable(config)) {
                    post { once(Reading.Unavailable("미지원(${config.accessType})")) }
                } else {
                    odometer.getCurrentDistanceDriven(
                        { km: Float? ->
                            post {
                                once(if (km == null) Reading.Unavailable("값 없음") else Reading.Value(Observation(km.toDouble(), Instant.now())))
                            }
                        },
                        { e: Exception -> post { fail(e, once, retry, callback) } },
                    )
                }
            },
            { e: Exception -> post { fail(e, once, retry, callback) } },
        )
    }

    private fun fail(e: Exception, once: Once<Reading>, retry: Boolean, callback: (Reading) -> Unit) {
        val code = errorCode(e)
        log("odometer failed code=$code msg=${e.message}")
        if (retry && code == "ERROR_SERVICE_CONNECTION_FAILURE" && once.cancel()) {
            main.postDelayed({ readOdometer(callback, retry = false) }, RETRY_MS)
            return
        }
        once(Reading.Unavailable(describe(code)))
    }

    /**
     * 연료 잔량 또는 SoC 구독. 처음 값은 get으로 한 번 받고 이후 변화는 리스너로 받는다.
     * @return 구독을 시작했으면 true. capability가 READ가 아니면 콜백은 오지 않는다.
     */
    fun subscribeEnergy(kind: EnergyKind, onValue: (Double?) -> Unit, onStatus: (String) -> Unit) {
        if (!started) return onStatus("차량 서비스 미연결")
        when (kind) {
            EnergyKind.FUEL -> {
                val fuel = vehicle.getFuel()
                fuel.checkFuelPercentCapabilities(
                    { config ->
                        if (!readable(config)) return@checkFuelPercentCapabilities post { onStatus("연료 잔량 미지원") }
                        fuel.getFuelPercent({ v: Int? -> post { onValue(v?.toDouble()) } }, { e -> post { onStatus(describe(errorCode(e))) } })
                        val listener = object : FuelPercentListener {
                            override fun onFuelPercentUpdated(percent: Int?) = post { onValue(percent?.toDouble()) }
                            override fun onFailed(e: Exception) = post { onStatus(describe(errorCode(e))) }
                        }
                        runCatching { fuel.registerFuelPercent(listener) }
                            .onSuccess { fuelListeners += listener; post { onStatus("연료 잔량 수신 중") } }
                            .onFailure { post { onStatus("연료 잔량 구독 실패") } }
                    },
                    { e -> post { onStatus(describe(errorCode(e))) } },
                )
            }
            EnergyKind.SOC -> {
                val ev = vehicle.getEvBattery()
                ev.checkEvBatteryStateOfChargeCapabilities(
                    { config ->
                        if (!readable(config)) return@checkEvBatteryStateOfChargeCapabilities post { onStatus("SoC 미지원") }
                        ev.getEvBatteryStateOfCharge({ v: Int? -> post { onValue(v?.toDouble()) } }, { e -> post { onStatus(describe(errorCode(e))) } })
                        val listener = object : EvBatteryStateOfChargeListener {
                            override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = post { onValue(soc?.toDouble()) }
                            override fun onFailed(e: Exception) = post { onStatus(describe(errorCode(e))) }
                        }
                        runCatching { ev.registerEvBatteryStateOfCharge(listener) }
                            .onSuccess { socListeners += listener; post { onStatus("SoC 수신 중") } }
                            .onFailure { post { onStatus("SoC 구독 실패") } }
                    },
                    { e -> post { onStatus(describe(errorCode(e))) } },
                )
            }
        }
    }

    private fun readable(config: CapabilityConfig) =
        config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    /** 결과를 한 번만 전달한다 (응답과 시간 초과 중 먼저 온 것) */
    private class Once<T>(private val target: (T) -> Unit) {
        private var done = false
        operator fun invoke(value: T) {
            if (done) return
            done = true
            target(value)
        }

        /** 아직 전달 전이면 막고 true. 재시도 전에 시간 초과 콜백을 무력화한다 */
        fun cancel(): Boolean {
            if (done) return false
            done = true
            return true
        }
    }

    companion object {
        private const val TIMEOUT_MS = 3_000L
        private const val RETRY_MS = 1_000L

        /** VehicleException.errorCode. 모든 onFailed가 VehicleException은 아니다 */
        fun errorCode(e: Exception): String? =
            runCatching { e.javaClass.getMethod("getErrorCode").invoke(e)?.toString() }.getOrNull()

        fun describe(code: String?): String = when (code) {
            "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA" -> "미지원"
            "ERROR_SECURITY_PERMISSION" -> "권한 없음"
            "ERROR_SERVICE_CONNECTION_FAILURE" -> "차량 서비스 연결 실패"
            "ERROR_DRIVING_RESTRICTED" -> "주행 중 제한"
            "ERROR_EMPTY_RESULT" -> "값 없음"
            "ERROR_INTERNAL_SERVICE" -> "일시 오류"
            null -> "수신 실패"
            else -> "수신 실패($code)"
        }
    }
}
