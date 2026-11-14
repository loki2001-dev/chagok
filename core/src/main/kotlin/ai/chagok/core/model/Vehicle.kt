package ai.chagok.core.model

import java.time.Instant

/** 동력원. 엔진오일 같은 내연기관 항목은 EV 프로필에 노출하지 않는다. */
enum class Powertrain(val label: String) {
    ICE("내연기관"), HEV("하이브리드"), PHEV("플러그인 하이브리드"), EV("전기차");

    val hasEngine: Boolean get() = this != EV
    val hasFuelTank: Boolean get() = this != EV
    val hasPlug: Boolean get() = this == PHEV || this == EV
}

/**
 * 앱 차량 프로필. id는 앱이 만든 차량 ID라 VIN 없이도 일지가 만들어진다.
 * vin은 CarInfo.getVINInfo 결과(선택). VIN이 바뀌거나 다르면 다른 차량 기록과 자동 병합하지 않는다.
 */
data class VehicleProfile(
    val id: String,
    val nickname: String,
    val powertrain: Powertrain,
    val modelName: String? = null,
    val vin: String? = null,
)

/** 가족 구성원. 작성자 표시와 구성원별 학습·읽음 기록에만 쓴다. */
data class Member(val id: String, val name: String)

/** 값의 출처. 한 일지 안에서도 필드마다 남긴다. */
enum class EvidenceSource(val label: String) { USER("사용자 입력"), VEHICLE("차량 수신"), APP_CALC("앱 계산") }

/** 필드 하나의 값과 근거. 차량 값이면 수신 시각이 붙는다. */
data class FieldEvidence<T>(
    val value: T,
    val unit: String?,
    val source: EvidenceSource,
    val receivedAt: Instant? = null,
)
