package io.chagok.app.drive

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context

/**
 * 주행 제한 상태 (AAOS CarUxRestrictions). 정차 전용 화면과 학습을 막는 데 쓴다.
 * 차량 서비스에 연결하지 못하면 안전하게 '제한'으로 본다.
 */
class DrivingState(context: Context, private val log: (String) -> Unit) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    /** true면 주행 중 제한이 걸려 있다 */
    var restricted: Boolean = true
        private set

    var available: Boolean = false
        private set

    private var listener: ((Boolean) -> Unit)? = null

    init {
        runCatching {
            val c = Car.createCar(context.applicationContext)
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            manager = m
            update(m.currentCarUxRestrictions)
            m.registerListener { r -> update(r) }
            available = true
        }.onFailure { log("CarUxRestrictions unavailable: ${it.message}") }
    }

    fun observe(onChange: (Boolean) -> Unit) {
        listener = onChange
        onChange(restricted)
    }

    private fun update(r: CarUxRestrictions?) {
        restricted = r?.isRequiresDistractionOptimization ?: true
        listener?.invoke(restricted)
    }

    fun release() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        listener = null
    }
}
