package ai.chagok.core.model

import java.time.Instant

/**
 * 일지 자동화에 쓰는 차량 값. Pleos Vehicle SDK의 공개 API(Normal 권한 범위)에 대응한다.
 * 정확한 반환 타입은 실차·에뮬레이터 확인 대상이라 어댑터가 Double로 정규화해 넣는다.
 */
enum class VehicleSignal(val unit: String, val sdk: String, val permission: String) {
    ODOMETER_KM("km", "Odometer.getCurrentDistanceDriven", "CAR_DRIVE"),
    FUEL_PERCENT("%", "Fuel.registerFuelPercent", "CAR_ENERGY"),
    EV_SOC_PERCENT("%", "EvBattery.registerEvBatteryStateOfCharge", "CAR_ENERGY"),
}

/**
 * 차량 값 한 번의 수신. value=null은 미수신(onComplete가 null을 주거나 ERROR_EMPTY_RESULT 등)이며 0으로 채우지 않는다.
 * receivedAt은 앱이 콜백을 받은 벽시계 시각. 표시와 최신성 판단에 쓴다.
 */
data class Observation(
    val vehicleId: String,
    val signal: VehicleSignal,
    val value: Double?,
    val receivedAt: Instant,
    val seq: Long = 0L,
)

/**
 * 신호별 수신 상태. 어댑터가 VehicleException.errorCode를 이렇게 옮긴다:
 * ERROR_SECURITY_PERMISSION → PERMISSION_DENIED, ERROR_NOT_SUPPORTED_* → UNSUPPORTED,
 * ERROR_DRIVING_RESTRICTED → DRIVING_RESTRICTED, 그 밖(서비스 연결 실패 등) → FAILED.
 */
enum class SourceStatus { NOT_STARTED, AVAILABLE, STALE, PERMISSION_DENIED, UNSUPPORTED, DRIVING_RESTRICTED, FAILED }
