package ai.chagok.core.diary

import ai.chagok.core.model.FieldEvidence
import ai.chagok.core.model.Powertrain
import java.time.Instant
import java.time.LocalDate

/** 관리 계획의 성격. 교환 주기 관리 후보와, 일괄 교환 주기 대신 점검 일정부터 두는 항목을 나눈다. */
enum class PlanKind { REPLACE, INSPECT, NONE }

/** 일지 항목. 엔진오일처럼 내연기관에만 있는 항목은 EV 프로필에 노출하지 않는다. */
enum class MaintenanceItem(val label: String, val planKind: PlanKind, val needsEngine: Boolean = false) {
    ENGINE_OIL("엔진오일", PlanKind.REPLACE, needsEngine = true),
    TIRE_ROTATION("타이어 위치 교환", PlanKind.REPLACE),
    TIRE_REPLACEMENT("타이어 교체", PlanKind.REPLACE),
    CABIN_FILTER("에어컨 필터", PlanKind.REPLACE),
    DRIVE_BELT("드라이브 벨트", PlanKind.REPLACE, needsEngine = true),
    TRANSMISSION_OIL("변속기 오일", PlanKind.REPLACE, needsEngine = true),
    BRAKE_FLUID("브레이크 오일", PlanKind.REPLACE),
    SPARK_PLUG("점화플러그", PlanKind.REPLACE, needsEngine = true),
    IGNITION_COIL("점화코일", PlanKind.REPLACE, needsEngine = true),
    BRAKE_PAD("브레이크 패드", PlanKind.INSPECT),
    BATTERY_12V("12V 배터리", PlanKind.INSPECT),
    WIPER("와이퍼", PlanKind.INSPECT),
    WASHER_FLUID("워셔액", PlanKind.NONE),
    OTHER("기타 정비", PlanKind.NONE);

    fun availableFor(p: Powertrain): Boolean = !needsEngine || p.hasEngine
}

enum class Wheel(val label: String) {
    FRONT_LEFT("앞 왼쪽"), FRONT_RIGHT("앞 오른쪽"), REAR_LEFT("뒤 왼쪽"), REAR_RIGHT("뒤 오른쪽");

    companion object {
        val FRONT = setOf(FRONT_LEFT, FRONT_RIGHT)
        val REAR = setOf(REAR_LEFT, REAR_RIGHT)
        val ALL = entries.toSet()
    }
}

enum class EntryType(val label: String) {
    MAINTENANCE("정비"), INSPECTION("점검"), REFILL("보충"), FUEL("주유"), CHARGE("충전"), SYMPTOM_MEMO("증상 메모"),
}

/** 초안 / 확정 / 취소. 확정된 기록만 비용·일정·도장에 반영된다. */
enum class EntryStatus { DRAFT, CONFIRMED, CANCELLED }

/** 금액. 일지에서 null은 "미입력"이며 0원이 아니다. */
data class Money(val amount: Long, val currency: String = "KRW")

/** 확정 후 수정 이력. 저장 후 수정은 변경 이력을 보존한다. */
data class Revision(val version: Int, val at: Instant, val byMemberId: String, val field: String, val before: String?, val after: String?)

/**
 * 일지 한 건.
 * odometer: 정비 시점의 누적 주행거리와 근거(차량 수신이면 수신 시각 포함).
 * referenceOdometer: 과거 기록에 붙는 "현재" km. 참고로만 보여 주고 일정 계산에 쓰지 않는다.
 * supersededOdometer: 사용자가 차량 km를 고치면 원래 차량 값을 여기 보존한다.
 * utterance: STT 인식 텍스트. 원음은 보관하지 않는다.
 * sourceKey: 같은 원인(음성 세션·후보)에서 기록이 두 번 생기지 않게 하는 키.
 */
data class DiaryEntry(
    val id: String,
    val vehicleId: String,
    val type: EntryType,
    val item: MaintenanceItem? = null,
    val wheels: Set<Wheel> = emptySet(),
    val performedOn: LocalDate,
    val dateApproximate: Boolean = false,
    /** 발화에 날짜가 없어 오늘로 둔 초안. 정비 직후가 맞는지 확인한다. */
    val dateAssumed: Boolean = false,
    val createdAt: Instant,
    val authorId: String,
    val cost: Money? = null,
    val odometer: FieldEvidence<Double>? = null,
    val referenceOdometer: FieldEvidence<Double>? = null,
    val supersededOdometer: FieldEvidence<Double>? = null,
    val note: String? = null,
    val utterance: String? = null,
    val status: EntryStatus = EntryStatus.DRAFT,
    val version: Int = 1,
    val revisions: List<Revision> = emptyList(),
    val sourceKey: String? = null,
    val candidateId: String? = null,
    val confirmedAt: Instant? = null,
    val confirmedBy: String? = null,
) {
    val isConfirmed: Boolean get() = status == EntryStatus.CONFIRMED

    /** 비용·리포트에서 묶는 이름. */
    val category: String
        get() = when (type) {
            EntryType.FUEL, EntryType.CHARGE, EntryType.SYMPTOM_MEMO -> type.label
            EntryType.REFILL -> "${item?.label ?: "액체류"} 보충"
            EntryType.INSPECTION -> "${item?.label ?: "차량"} 점검"
            EntryType.MAINTENANCE -> item?.label ?: type.label
        }
}
