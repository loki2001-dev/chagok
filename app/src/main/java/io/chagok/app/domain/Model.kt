package io.chagok.app.domain

import java.time.Instant
import java.time.LocalDate

/** 동력원. 엔진오일 등 내연기관 항목은 EV 프로필에 노출하지 않는다 (기획서 2장) */
enum class Powertrain(val label: String) {
    ICE("내연기관"),
    HYBRID("하이브리드"),
    EV("전기차"),
}

/** VIN 없이도 앱 차량 ID로 일지를 만든다. VIN이 달라도 다른 차량 기록과 자동 병합하지 않는다 */
data class VehicleProfile(
    val id: String,
    val name: String,
    val powertrain: Powertrain,
    val model: String? = null,
    val vin: String? = null,
)

/** 필드 값의 출처 (기획서 5장: 차량 관측, 사용자 확인, 앱 계산을 구분) */
enum class Source(val label: String) {
    VEHICLE("차량 수신"),
    USER("사용자 입력"),
    APP("앱 계산"),
}

/** 차량에서 받은 값 하나. 수신 시각을 함께 남긴다 */
data class Observation(val value: Double, val receivedAt: Instant)

/**
 * 일지 필드 하나의 근거 (기획서 9장 FieldEvidence).
 * 사용자가 차량 km를 고치면 [source]는 USER가 되고, 원래 관측값은 [observed]에 그대로 남는다.
 */
data class FieldEvidence(
    val value: Double,
    val unit: String,
    val source: Source,
    val receivedAt: Instant? = null,
    val observed: Observation? = null,
)

enum class ItemKind {
    /** 거리·기간 주기로 관리하는 교환 항목 */
    REPLACE,

    /** 일괄 교환 주기 대신 점검 일정을 두는 항목 */
    INSPECT,

    /** 주유·충전 */
    ENERGY,

    /** 사용자 보충 이력. 차량 경고 해제와 별개 */
    REFILL,

    OTHER,
}

/** 정비·기록 항목. [powertrains]에 없는 동력원 프로필에는 보이지 않는다 */
enum class Item(val label: String, val kind: ItemKind, val powertrains: Set<Powertrain>) {
    ENGINE_OIL("엔진오일 교환", ItemKind.REPLACE, setOf(Powertrain.ICE, Powertrain.HYBRID)),
    TIRE_ROTATION("타이어 위치 교환", ItemKind.REPLACE, Powertrain.entries.toSet()),
    TIRE_REPLACE("타이어 교체", ItemKind.OTHER, Powertrain.entries.toSet()),
    BRAKE_PAD_CHECK("브레이크 패드 점검", ItemKind.INSPECT, Powertrain.entries.toSet()),
    BATTERY_12V_CHECK("12V 배터리 점검", ItemKind.INSPECT, Powertrain.entries.toSet()),
    WIPER_CHECK("와이퍼 점검", ItemKind.INSPECT, Powertrain.entries.toSet()),
    WASHER_REFILL("워셔액 보충", ItemKind.REFILL, Powertrain.entries.toSet()),
    REFUEL("주유", ItemKind.ENERGY, setOf(Powertrain.ICE, Powertrain.HYBRID)),
    CHARGE("충전", ItemKind.ENERGY, setOf(Powertrain.EV)),
    OTHER("기타 정비", ItemKind.OTHER, Powertrain.entries.toSet()),

    /** 이상한 소리·떨림 같은 일반 증상 메모. 진단하지 않는다 */
    SYMPTOM_NOTE("증상 메모", ItemKind.OTHER, Powertrain.entries.toSet());

    fun availableFor(p: Powertrain) = p in powertrains
}

enum class Wheel(val label: String) {
    FRONT_LEFT("앞 왼쪽"),
    FRONT_RIGHT("앞 오른쪽"),
    REAR_LEFT("뒤 왼쪽"),
    REAR_RIGHT("뒤 오른쪽"),
}

enum class EntryStatus(val label: String) {
    DRAFT("초안"),
    CONFIRMED("확정"),
    CANCELLED("취소"),
}

/** 확정 후 수정 시 이전 값을 남기는 변경 이력 */
data class Revision(val version: Int, val changedAt: Instant, val summary: String)

/** 일지 한 건 (기획서 9장 DiaryEntry) */
data class DiaryEntry(
    val id: String,
    val vehicleId: String,
    val item: Item,
    /** 실제 수행일 */
    val performedOn: LocalDate,
    /** 작성 시각 */
    val createdAt: Instant,
    val odometer: FieldEvidence?,
    /** 원 단위 금액. null이면 0원이 아니라 미입력 */
    val cost: Long?,
    val currency: String = "KRW",
    val status: EntryStatus,
    val author: String,
    val version: Int = 1,
    val history: List<Revision> = emptyList(),
    val wheels: Set<Wheel> = emptySet(),
    /** 주유·충전 후보에서 확정된 경우 그 후보 ID. 같은 원인에서 일지가 두 번 생기지 않게 한다 */
    val candidateId: String? = null,
    /** 인식 텍스트. 원음은 저장하지 않는다 */
    val spokenText: String? = null,
    val memo: String? = null,
)

/** 정비 계획 (기획서 9장 MaintenancePlan). 주기는 사용자가 매뉴얼 등을 보고 정한다 */
data class MaintenancePlan(
    val vehicleId: String,
    val item: Item,
    val intervalKm: Int?,
    val intervalMonths: Int?,
    /** 주기 출처. 예: "차량 매뉴얼 2024년식" */
    val source: String,
    val setOn: LocalDate,
    val notificationsOn: Boolean = true,
    /** 사용자가 미룬 날짜. 이 날까지 브리핑에서 접어 둔다 */
    val snoozedUntil: LocalDate? = null,
)

enum class EnergyKind(val label: String, val unit: String) {
    FUEL("연료 잔량", "%"),
    SOC("배터리 SoC", "%"),
}

enum class CandidateState(val label: String) {
    PENDING("확인 대기"),
    CONFIRMED("확정"),
    EXCLUDED("제외"),
}

/**
 * 잔량 상승에서 만든 주유·충전 후보 (기획서 9장 DetectionCandidate).
 * 후보는 비용 합계와 관리 완료에 들어가지 않는다.
 */
data class DetectionCandidate(
    val id: String,
    val vehicleId: String,
    val kind: EnergyKind,
    val before: Observation,
    val after: Observation,
    val state: CandidateState,
    /** 두 관측 사이에 수신 공백이 길었는지. 길면 '마지막 관측 이후 잔량 증가'로만 설명한다 */
    val gapLong: Boolean,
    val linkedEntryId: String? = null,
) {
    val rise: Double get() = after.value - before.value
}

enum class Topic(val label: String, val available: Boolean) {
    FIRST_DIARY("일지 첫걸음", true),
    READ_INTERVAL("교환 주기 읽기", true),
    TIRE_BASICS("타이어 알아보기", false),
    MY_ENERGY("내 차의 에너지", false),
}

/** 학습 진행 (기획서 9장 LearningProgress). 차량 상태와는 별개다 */
data class LearningProgress(
    val user: String,
    val topic: Topic,
    val contentVersion: Int,
    val completedAt: Instant,
    val evidenceEntryId: String,
    /** 근거 일지가 취소되어 다시 확인해야 하는 도장 */
    val needsReview: Boolean = false,
)
