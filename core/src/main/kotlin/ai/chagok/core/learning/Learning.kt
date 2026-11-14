package ai.chagok.core.learning

import ai.chagok.core.diary.DiaryEntry
import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.model.Powertrain
import ai.chagok.core.schedule.MaintenancePlan
import ai.chagok.core.schedule.ScheduleCalculator
import ai.chagok.core.schedule.ScheduleStatus
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 도감 주제. MVP(공모전 시연)는 앞의 두 주제만 화면 흐름으로 구현하고 나머지는 콘텐츠 확장 후보다. */
enum class Topic(val title: String, val inMvp: Boolean) {
    FIRST_DIARY("일지 첫걸음", true),
    READ_INTERVAL("교환 주기 읽기", true),
    TIRE_BASICS("타이어 알아보기", false),
    MY_ENERGY("내 차의 에너지", false),
}

data class Question(val id: String, val prompt: String, val choices: List<String>, val answer: Int, val hint: String)

/** 일지 한 건에서 시작하는 짧은 학습. basisEntryId가 그 근거 기록이다. */
data class Lesson(val topic: Topic, val contentVersion: Int, val basisEntryId: String, val explanation: String, val questions: List<Question>)

/**
 * 구성원별 학습 진행. 도장은 주제당 한 번. 학습 완료는 정비 완료와 별개이며 차량 상태를 평가하지 않는다.
 * basisEntryId: 근거 일지. 그 일지가 취소되면 다른 유효 근거로 옮기거나 연결을 해제한다(도장은 유지).
 */
data class LearningProgress(
    val memberId: String,
    val topic: Topic,
    val contentVersion: Int,
    val completedAt: Instant,
    val basisEntryId: String?,
)

/**
 * 관리 도감. 정비 일지를 쓰는 목적이 먼저고, 저장 이후 관련 지식을 고를 수 있게 한다.
 * 보상은 학습에만 연결한다: 같은 주제 반복에 도장을 더 주지 않고, 운전·주유·교환 횟수나 금액으로 보상하지 않는다.
 * 연속 출석·순위·벌점·확률 보상은 없다. 정차 중에만 제공한다(Engine이 막는다).
 */
object LearningCatalog {
    const val CONTENT_VERSION = 1
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd")

    /** 이 주제의 근거가 될 수 있는 확정 일지. 없으면 그 주제는 아직 열리지 않는다. */
    fun basis(topic: Topic, vehicleId: String, powertrain: Powertrain, entries: List<DiaryEntry>, plans: List<MaintenancePlan>, today: LocalDate): DiaryEntry? {
        val valid = entries.filter { it.vehicleId == vehicleId && it.isConfirmed }
        return when (topic) {
            Topic.FIRST_DIARY -> valid.filter { it.type != EntryType.SYMPTOM_MEMO }.minByOrNull { it.confirmedAt ?: it.createdAt }
            Topic.READ_INTERVAL -> plans.asSequence()
                .filter { it.vehicleId == vehicleId && it.isSet && it.item.availableFor(powertrain) }
                .sortedBy { if (it.item == MaintenanceItem.ENGINE_OIL) 0 else 1 + it.item.ordinal }
                .mapNotNull { p -> ScheduleCalculator.baseEntry(p, valid)?.takeIf { intervalStatus(p, valid, today) != null } }
                .firstOrNull()
            Topic.TIRE_BASICS, Topic.MY_ENERGY -> null
        }
    }

    private fun intervalStatus(p: MaintenancePlan, entries: List<DiaryEntry>, today: LocalDate): ScheduleStatus? =
        ScheduleCalculator.status(p, entries, null, today).takeIf { it.nextKm != null || it.nextDate != null }

    fun lesson(topic: Topic, basis: DiaryEntry, plans: List<MaintenancePlan>, entries: List<DiaryEntry>, today: LocalDate): Lesson? = when (topic) {
        Topic.FIRST_DIARY -> firstDiary(basis)
        Topic.READ_INTERVAL -> plans.firstOrNull { it.vehicleId == basis.vehicleId && it.item == basis.item }
            ?.let { p -> intervalStatus(p, entries, today)?.let { readInterval(basis, it) } }
        else -> null
    }

    private fun firstDiary(e: DiaryEntry): Lesson {
        val qs = ArrayList<Question>()
        e.odometer?.let {
            qs += Question(
                "km", "이 기록의 ${km(it.value)}는 무엇인가요?",
                listOf("정비할 때까지 차가 달린 누적 거리", "다음 정비까지 남은 거리", "그날 하루 달린 거리"), 0,
                "계기판의 총 주행거리(ODO)와 같은 값이에요. 다음 관리 시점을 계산하는 기준이 돼요.",
            )
        }
        qs += Question(
            "date", "${e.performedOn.format(dateFmt)}는 무엇을 뜻하나요?",
            listOf("실제로 관리한 날", "앱에 처음 저장한 날", "다음 관리 예정일"), 0,
            "일지의 날짜는 실제로 관리한 날이에요. 저장한 날과 다를 수 있어요.",
        )
        qs += Question(
            "item", "이 기록에서 '${e.category}'는 무엇인가요?",
            listOf("이번에 한 관리 항목", "차가 알려 준 고장 이름", "다음에 할 관리"), 0,
            "정비 항목은 내가 확인한 관리 내용이에요. 차가 진단한 결과가 아니에요.",
        )
        return Lesson(
            Topic.FIRST_DIARY, CONTENT_VERSION, e.id,
            "일지 한 건은 날짜(언제), 주행거리(그때까지 달린 거리), 항목(무엇을)으로 읽어요.", qs,
        )
    }

    private fun readInterval(e: DiaryEntry, s: ScheduleStatus): Lesson {
        val label = s.plan.item.label
        val kmText = s.nextKm?.let { km(it) }
        val dateText = s.nextDate?.format(dateFmt)
        val interval = listOfNotNull(s.plan.intervalKm?.let { km(it) }, s.plan.intervalMonths?.let { "${it}개월" }).joinToString(" · ")
        val qs = ArrayList<Question>()
        if (kmText != null && dateText != null) {
            qs += Question(
                "first", "다음 $label 관리는 언제인가요?",
                listOf("$kmText 또는 $dateText 중 먼저 오는 때", "$kmText 와 $dateText 가 모두 지났을 때", "$kmText 에 도달했을 때만"), 0,
                "두 조건 중 하나만 충족돼도 관리 시점이에요.",
            )
            qs += Question(
                "date-first", "아직 $kmText 에 못 미쳤는데 $dateText 가 지났다면?",
                listOf("관리 시점이 됐어요", "아직 아니에요, 거리가 남았어요"), 0,
                "날짜 조건이 먼저 왔으니 도래 상태예요. 거리가 남았다고 미루지 않아요.",
            )
        } else {
            val only = kmText ?: dateText!!
            qs += Question(
                "only", "다음 $label 관리는 언제인가요?",
                listOf("$only", "설정한 주기의 두 배가 지났을 때", "차가 경고를 띄울 때"), 0,
                "지금은 ${if (kmText != null) "거리" else "기간"} 조건 하나로 관리하고 있어요.",
            )
        }
        return Lesson(
            Topic.READ_INTERVAL, CONTENT_VERSION, e.id,
            "$label 은(는) 거리와 기간 중 먼저 도래한 조건으로 관리해요. 이 일정은 직접 설정한 주기($interval)로 계산했어요. 모든 차의 권장 주기는 아니에요.",
            qs,
        )
    }

    private fun km(v: Double): String = "%,dkm".format(Math.round(v))
}
