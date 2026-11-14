package ai.chagok.core.voice

import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.diary.Wheel
import java.time.LocalDate
import java.time.MonthDay
import kotlin.math.roundToLong

/** 날짜를 어떻게 말했는지. 확인 화면이 무엇을 물을지 정한다. */
enum class DateKind {
    /** 날짜를 말하지 않았다. 오늘로 두되 정비 직후인지 확인한다. */
    UNSPOKEN,
    /** "오늘", "방금" */
    TODAY,
    /** "어제", "3일 전"처럼 날짜가 정해지는 과거 */
    RELATIVE,
    /** "지난주", "지난달"처럼 날짜가 대략인 과거. 확정 전에 날짜를 확인받는다. */
    APPROXIMATE,
    /** "11월 3일" */
    EXPLICIT,
}

/**
 * stated: 발화에 날짜 표현이 있었는지. approximate: "지난주"처럼 정확한 날이 아니라 확정 전에 날짜를 확인받아야 하는지.
 * kind: 위 둘을 포함한 세분류. 직접 만들 때 생략하면 stated·approximate에서 정한다.
 */
data class DateGuess(
    val date: LocalDate,
    val stated: Boolean,
    val approximate: Boolean,
    val kind: DateKind = when { !stated -> DateKind.UNSPOKEN; approximate -> DateKind.APPROXIMATE; else -> DateKind.RELATIVE },
)

/** STT 텍스트를 해석한 결과. 인식 완료는 저장 완료가 아니다: Draft는 확인을 거쳐야 일지가 된다. */
sealed class VoiceIntent {
    data class Draft(
        val type: EntryType,
        val item: MaintenanceItem?,
        val wheels: Set<Wheel>,
        val wheelCount: Int?,
        val date: DateGuess,
        val cost: Long?,
        val memo: String?,
    ) : VoiceIntent()
    /** "8만 원이 아니라 7만 원". oldAmount가 있으면 고칠 대상을 고를 때 쓴다. */
    data class CorrectCost(val newAmount: Long, val oldAmount: Long?) : VoiceIntent()
    data object CancelDraft : VoiceIntent()
    data object QuerySchedule : VoiceIntent()
    data class Unrecognized(val text: String) : VoiceIntent()
}

/** 한국어 금액: "8만 원", "80,000원", "8만 5천 원", "칠만 원", "만오천원", "1.5만 원". */
object KoreanNumber {
    private val digit = mapOf('영' to 0, '공' to 0, '일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '륙' to 6, '칠' to 7, '팔' to 8, '구' to 9)
    private val small = mapOf('십' to 10.0, '백' to 100.0, '천' to 1000.0)
    private val big = mapOf('만' to 10_000.0, '억' to 100_000_000.0)

    fun parse(raw: String): Long? {
        val s = raw.replace(",", "").replace(" ", "")
        if (s.isEmpty()) return null
        var total = 0.0; var section = 0.0; var num: Double? = null; var any = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() -> {
                    var j = i
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                    num = s.substring(i, j).toDoubleOrNull() ?: return null; any = true; i = j; continue
                }
                c in digit -> { num = digit.getValue(c).toDouble(); any = true }
                c in small -> { section += (num ?: 1.0) * small.getValue(c); num = null; any = true }
                c in big -> {
                    val v = section + (num ?: 0.0)
                    total += (if (v == 0.0) 1.0 else v) * big.getValue(c); section = 0.0; num = null; any = true
                }
                else -> return null
            }
            i++
        }
        if (!any) return null
        return (total + section + (num ?: 0.0)).roundToLong()
    }
}

/** 날짜 표현. 표현이 없으면 오늘(stated=false). 미래로 해석되는 "M월 D일"은 작년으로 본다. */
object DateExpression {
    private val monthDay = Regex("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일")
    private val daysAgo = Regex("(\\d+)\\s*일\\s*전")

    fun parse(text: String, today: LocalDate): DateGuess {
        monthDay.find(text)?.let { m ->
            val md = runCatching { MonthDay.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
            if (md != null) {
                var d = md.atYear(today.year)
                if (d.isAfter(today)) d = md.atYear(today.year - 1)
                return DateGuess(d, stated = true, approximate = false, kind = DateKind.EXPLICIT)
            }
        }
        daysAgo.find(text)?.let { return DateGuess(today.minusDays(it.groupValues[1].toLong()), true, false, DateKind.RELATIVE) }
        return when {
            Regex("그저께|그제").containsMatchIn(text) -> DateGuess(today.minusDays(2), true, false, DateKind.RELATIVE)
            text.contains("어제") -> DateGuess(today.minusDays(1), true, false, DateKind.RELATIVE)
            Regex("지난\\s*주|저번\\s*주").containsMatchIn(text) -> DateGuess(today.minusWeeks(1), true, true, DateKind.APPROXIMATE)
            Regex("지난\\s*달|저번\\s*달").containsMatchIn(text) -> DateGuess(today.minusMonths(1), true, true, DateKind.APPROXIMATE)
            Regex("오늘|방금|아까").containsMatchIn(text) -> DateGuess(today, true, false, DateKind.TODAY)
            else -> DateGuess(today, false, false, DateKind.UNSPOKEN)
        }
    }
}

/**
 * 발화 사전(기획서 07)을 해석한다. 규칙 기반이며 STT가 숫자로 받아쓴 금액("8만 원")과 한글 금액을 모두 받는다.
 * 해석은 초안만 만든다. 항목·날짜·금액·주행거리는 확인 화면에서 사용자가 확정한다.
 */
object UtteranceParser {
    /** 위에서부터 처음 맞는 항목. "변속기 오일"이 엔진오일로 잡히지 않도록 구체적인 이름을 먼저 둔다. */
    private val itemAliases: List<Pair<Regex, MaintenanceItem>> = listOf(
        Regex("위치\\s*교환|로테이션|위치\\s*바꿨|자리\\s*바꿨") to MaintenanceItem.TIRE_ROTATION,
        Regex("드라이브\\s*벨트|구동\\s*벨트") to MaintenanceItem.DRIVE_BELT,
        Regex("(변속기|미션|기어)\\s*오일") to MaintenanceItem.TRANSMISSION_OIL,
        Regex("브레이크\\s*(오일|액)") to MaintenanceItem.BRAKE_FLUID,
        Regex("점화\\s*코일") to MaintenanceItem.IGNITION_COIL,
        Regex("점화\\s*플러그|스파크\\s*플러그") to MaintenanceItem.SPARK_PLUG,
        Regex("엔진\\s*오일|(?<![가-힣])오일") to MaintenanceItem.ENGINE_OIL,
        Regex("브레이크\\s*패드|(?<![가-힣])패드") to MaintenanceItem.BRAKE_PAD,
        Regex("에어컨\\s*필터|에어\\s*필터|캐빈\\s*필터|항균\\s*필터") to MaintenanceItem.CABIN_FILTER,
        Regex("와이퍼") to MaintenanceItem.WIPER,
        Regex("워셔액") to MaintenanceItem.WASHER_FLUID,
        Regex("배터리") to MaintenanceItem.BATTERY_12V,
        Regex("타이어") to MaintenanceItem.TIRE_REPLACEMENT,
    )
    private val number = "(?:[0-9][0-9,]*(?:\\.[0-9]+)?|[영공일이삼사오육륙칠팔구십백천만억]+)"
    private val amount = Regex("(?<![가-힣0-9])($number(?:\\s?$number)*)\\s?원")

    private val cancel = Regex("취소|없던\\s*걸로|그만\\s*할게")
    /** "언제"가 들어간 말은 일정 조회로 본다("다음 엔진오일 교환은 언제야"). */
    private val query = Regex("언제|다음\\s*(관리|정비|교환|교체|점검)|일정\\s*(알려|뭐|어때)")
    private val correction = Regex("아니라|아니고|말고|(으로|로)\\s*(바꿔|수정|고쳐|정정)")
    private val fuel = Regex("주유|기름\\s*(을\\s*)?(넣|채웠)|가득\\s*넣")
    private val charge = Regex("충전")
    private val symptom = Regex("소리|떨림|떨려|진동|냄새|끼익|덜컹|삐걱|이상해|이상한|경고등")
    private val replaceVerb = Regex("갈았|갈아|교체|교환|바꿨|바꿈|새로\\s*(샀|달|끼)")
    private val inspectVerb = Regex("점검|검사|확인\\s*(했|받)")
    private val refillVerb = Regex("채웠|보충|넣었|채움")
    private val serviceVerb = Regex("수리|정비")

    fun parse(text: String, today: LocalDate): VoiceIntent {
        val t = text.trim().replace(Regex("[.!?…]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return VoiceIntent.Unrecognized(text)
        val item = itemAliases.firstOrNull { it.first.containsMatchIn(t) }?.second
        val amounts = amounts(t)

        if (cancel.containsMatchIn(t) && item == null) return VoiceIntent.CancelDraft
        if (query.containsMatchIn(t)) return VoiceIntent.QuerySchedule
        if (amounts.isNotEmpty() && correction.containsMatchIn(t) && !replaceVerb.containsMatchIn(t)) {
            return VoiceIntent.CorrectCost(amounts.last(), if (amounts.size >= 2) amounts.first() else null)
        }

        val date = DateExpression.parse(t, today)
        val cost = amounts.lastOrNull()
        fun draft(type: EntryType, it: MaintenanceItem?, memo: String? = null): VoiceIntent.Draft {
            val (wheels, count) = if (it == MaintenanceItem.TIRE_REPLACEMENT) wheels(t) else emptySet<Wheel>() to null
            return VoiceIntent.Draft(type, it, wheels, count, date, cost, memo)
        }

        if (fuel.containsMatchIn(t)) return draft(EntryType.FUEL, null)
        if (charge.containsMatchIn(t) && !(item == MaintenanceItem.BATTERY_12V && replaceVerb.containsMatchIn(t))) return draft(EntryType.CHARGE, null)
        if (symptom.containsMatchIn(t) && !replaceVerb.containsMatchIn(t)) return draft(EntryType.SYMPTOM_MEMO, item, memo = t)
        if (item == null) {
            // 항목은 몰라도 정비했다는 말이나 금액이 있으면 "기타 정비"로 받아 두고 원문을 메모로 남긴다
            val serviced = replaceVerb.containsMatchIn(t) || inspectVerb.containsMatchIn(t) || serviceVerb.containsMatchIn(t)
            return if (serviced || cost != null) draft(EntryType.MAINTENANCE, MaintenanceItem.OTHER, memo = t) else VoiceIntent.Unrecognized(text)
        }
        return when {
            refillVerb.containsMatchIn(t) && !replaceVerb.containsMatchIn(t) -> draft(EntryType.REFILL, item)
            inspectVerb.containsMatchIn(t) && !replaceVerb.containsMatchIn(t) -> draft(EntryType.INSPECTION, item)
            item == MaintenanceItem.WASHER_FLUID -> draft(EntryType.REFILL, item)
            else -> draft(EntryType.MAINTENANCE, item)
        }
    }

    /** 금액들. 항목 이름 속 한글 숫자("오일"의 오·일)를 금액으로 읽지 않도록 항목 이름을 먼저 지운다. */
    fun amounts(text: String): List<Long> {
        var s = text
        for ((re, _) in itemAliases) s = re.replace(s, " ")
        return amount.findAll(s).mapNotNull { KoreanNumber.parse(it.groupValues[1]) }.filter { it > 0 }.toList()
    }

    /** "앞 타이어 두 개" → 앞 두 바퀴. 위치를 말하지 않았으면 빈 집합(확인 화면에서 묻는다). */
    fun wheels(t: String): Pair<Set<Wheel>, Int?> {
        val count = when {
            Regex("(네|4)\\s*(개|짝)|네\\s*바퀴|4\\s*바퀴|전부|전체|모두|다\\s*(갈|교체|바꿨)").containsMatchIn(t) -> 4
            Regex("(두|2)\\s*(개|짝)|두\\s*바퀴|2\\s*바퀴").containsMatchIn(t) -> 2
            Regex("(한|1)\\s*(개|짝)|한\\s*바퀴").containsMatchIn(t) -> 1
            else -> null
        }
        val front = t.contains("앞"); val rear = t.contains("뒤")
        val left = Regex("왼쪽|운전석").containsMatchIn(t); val right = Regex("오른쪽|조수석").containsMatchIn(t)
        val set = when {
            count == 4 || (front && rear) -> Wheel.ALL
            front && left -> setOf(Wheel.FRONT_LEFT)
            front && right -> setOf(Wheel.FRONT_RIGHT)
            rear && left -> setOf(Wheel.REAR_LEFT)
            rear && right -> setOf(Wheel.REAR_RIGHT)
            front -> Wheel.FRONT
            rear -> Wheel.REAR
            else -> emptySet()
        }
        return set to count
    }
}
