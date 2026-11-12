package io.chagok.app.domain

import java.time.format.DateTimeFormatter

/** 정차 중 선택형 미션 하나. 오답은 힌트 후 다시 시도하며 감점하지 않는다 */
data class Mission(
    val topic: Topic,
    val evidenceEntryId: String,
    /** 미션 앞에 보여 주는 설명 한 장. 사용자의 일지 값으로 채운다 */
    val card: String,
    val question: String,
    val choices: List<String>,
    val answerIndex: Int,
    val hint: String,
)

/**
 * 관리 도감 콘텐츠 (기획서 6장). 공모전 시연 범위는 '일지 첫걸음'과 '교환 주기 읽기' 두 주제다.
 * 설명은 앱의 일지 읽는 법이며, 특정 차의 권장 주기나 고장 진단을 말하지 않는다.
 */
object LearningCatalog {
    const val CONTENT_VERSION = 1
    private val DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd")

    fun firstDiary(entry: DiaryEntry): Mission {
        val km = entry.odometer?.let { "%,.0fkm".format(it.value) }
        val card = buildString {
            append("일지 한 건은 세 가지로 읽어요.\n")
            append("· 날짜 ${entry.performedOn.format(DATE)}: 정비를 실제로 한 날\n")
            append("· 주행거리 ${km ?: "미입력"}: 그때까지 차가 달린 누적 거리\n")
            append("· 항목 ${entry.item.label}: 무엇을 했는지")
        }
        return if (km != null) {
            Mission(
                topic = Topic.FIRST_DIARY,
                evidenceEntryId = entry.id,
                card = card,
                question = "이 기록의 $km 는 무엇을 뜻할까요?",
                choices = listOf("정비할 때까지의 누적 주행거리", "다음 정비까지 남은 거리", "이번 달에 달린 거리"),
                answerIndex = 0,
                hint = "계기판의 총 주행거리(ODO)와 같은 값이에요.",
            )
        } else {
            Mission(
                topic = Topic.FIRST_DIARY,
                evidenceEntryId = entry.id,
                card = card,
                question = "이 기록의 날짜 ${entry.performedOn.format(DATE)} 는 무엇을 뜻할까요?",
                choices = listOf("정비를 실제로 한 날", "일지를 작성한 날", "다음 정비 예정일"),
                answerIndex = 0,
                hint = "지난 정비를 나중에 적어도 날짜는 정비한 날로 남겨요.",
            )
        }
    }

    fun readInterval(status: ScheduleStatus): Mission? {
        val basis = status.basis ?: return null
        if (status.plan.intervalKm == null || status.plan.intervalMonths == null) return null
        val nextKm = status.nextKm?.let { "%,.0fkm".format(it) } ?: "계산 보류(주행거리 없음)"
        val nextDate = status.nextDate?.format(DATE) ?: "-"
        val card = buildString {
            append("${status.plan.item.label}은 거리와 기간 중 먼저 도래한 조건으로 관리해요.\n")
            append("· 다음 거리: $nextKm\n")
            append("· 다음 날짜: $nextDate\n")
            append("주기 ${"%,d".format(status.plan.intervalKm)}km · ${status.plan.intervalMonths}개월은 직접 설정한 값이에요 (출처: ${status.plan.source}).")
        }
        return Mission(
            topic = Topic.READ_INTERVAL,
            evidenceEntryId = basis.id,
            card = card,
            question = "다음 ${status.plan.item.label}은 언제 챙기면 될까요?",
            choices = listOf(
                "$nextKm 또는 $nextDate 중 먼저 오는 때",
                "$nextKm 와 $nextDate 가 모두 지난 뒤",
                "$nextDate 만 보면 된다",
            ),
            answerIndex = 0,
            hint = "두 조건 중 하나라도 충족되면 '도래'예요.",
        )
    }
}
