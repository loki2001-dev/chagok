package io.chagok.app.domain

import java.time.LocalDate
import kotlin.math.roundToLong

/** 발화에서 읽은 날짜와 그 확실성 */
data class SpokenDate(val date: LocalDate, val kind: Kind) {
    enum class Kind {
        /** 날짜를 말하지 않았다. 오늘로 두되 정비 직후인지 확인한다 */
        UNSPOKEN,

        /** "오늘" */
        TODAY,

        /** "어제", "3일 전"처럼 날짜가 정해지는 과거 */
        RELATIVE,

        /** "지난주", "지난달"처럼 날짜가 대략인 과거. 날짜를 꼭 확인한다 */
        APPROXIMATE,

        /** "11월 3일" */
        EXPLICIT,
    }

    val isToday: Boolean get() = kind == Kind.UNSPOKEN || kind == Kind.TODAY
}

/** 기획서 7장 발화 사전의 해석 결과 */
sealed interface SpokenIntent {
    data class Record(
        val item: Item?,
        val date: SpokenDate,
        val cost: Long?,
        val wheels: Set<Wheel> = emptySet(),
        val memo: String? = null,
    ) : SpokenIntent

    /** "8만 원이 아니라 7만 원" */
    data class CorrectCost(val cost: Long) : SpokenIntent

    /** "취소해" */
    data object Cancel : SpokenIntent

    /** "다음 관리는 언제야?" */
    data object QueryNext : SpokenIntent

    data class Unknown(val text: String) : SpokenIntent
}

/**
 * STT 텍스트를 정비 항목·날짜·금액·수정 의도로 해석한다. 규칙 기반이며 저장은 하지 않는다.
 * 인식 완료는 저장 완료가 아니다: 결과는 언제나 사용자가 확인할 초안의 재료다.
 */
class UtteranceParser(private val today: () -> LocalDate) {

    fun parse(raw: String): SpokenIntent {
        val text = raw.trim().replace(Regex("[.!?…]+"), " ").replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return SpokenIntent.Unknown(raw)
        val compact = text.replace(" ", "")

        if (compact.contains("아니라") || compact.contains("아니고")) {
            val cost = amounts(text).lastOrNull()
            if (cost != null) return SpokenIntent.CorrectCost(cost)
        }

        val item = detectItem(compact)
        if (item == null && (compact.startsWith("취소") || compact.endsWith("취소해") || compact == "취소")) {
            return SpokenIntent.Cancel
        }
        if (compact.contains("언제") || (item == null && compact.contains("다음") && compact.contains("관리"))) {
            return SpokenIntent.QueryNext
        }
        if (item == null && Regex("(으로|로)(수정|바꿔|고쳐)").containsMatchIn(compact)) {
            val cost = amounts(text).lastOrNull()
            if (cost != null) return SpokenIntent.CorrectCost(cost)
        }

        val cost = amounts(text).firstOrNull()
        val date = detectDate(compact)
        return when {
            item != null -> SpokenIntent.Record(
                item = item,
                date = date,
                cost = cost,
                wheels = if (item == Item.TIRE_REPLACE) detectWheels(compact) else emptySet(),
                memo = if (item == Item.OTHER || item == Item.SYMPTOM_NOTE) text else null,
            )
            MAINTENANCE_VERB.containsMatchIn(compact) || cost != null ->
                SpokenIntent.Record(Item.OTHER, date, cost, memo = text)
            else -> SpokenIntent.Unknown(raw)
        }
    }

    private fun detectItem(t: String): Item? = when {
        t.contains("워셔") -> Item.WASHER_REFILL
        t.contains("타이어") && (t.contains("위치") || t.contains("로테이션")) -> Item.TIRE_ROTATION
        t.contains("타이어") && Regex("교체|갈았|갈아|바꿨|바꾸|새로").containsMatchIn(t) -> Item.TIRE_REPLACE
        t.contains("엔진오일") -> Item.ENGINE_OIL
        t.contains("오일") && !Regex("미션|변속|브레이크|기어").containsMatchIn(t) -> Item.ENGINE_OIL
        t.contains("브레이크") && t.contains("패드") && CHECK_VERB.containsMatchIn(t) -> Item.BRAKE_PAD_CHECK
        t.contains("배터리") && CHECK_VERB.containsMatchIn(t) -> Item.BATTERY_12V_CHECK
        t.contains("와이퍼") && CHECK_VERB.containsMatchIn(t) -> Item.WIPER_CHECK
        t.contains("주유") || t.contains("기름넣") || t.contains("기름을넣") -> Item.REFUEL
        t.contains("충전") -> Item.CHARGE
        Regex("소리|떨림|떨려|진동|냄새|덜컹|끼익").containsMatchIn(t) -> Item.SYMPTOM_NOTE
        else -> null
    }

    private fun detectWheels(t: String): Set<Wheel> {
        val front = setOf(Wheel.FRONT_LEFT, Wheel.FRONT_RIGHT)
        val rear = setOf(Wheel.REAR_LEFT, Wheel.REAR_RIGHT)
        return when {
            Regex("네개|4개|네짝|전부|전체|모두|다갈").containsMatchIn(t) -> front + rear
            t.contains("앞") && t.contains("뒤") -> front + rear
            t.contains("앞") -> front
            t.contains("뒤") -> rear
            else -> emptySet()
        }
    }

    private fun detectDate(t: String): SpokenDate {
        val now = today()
        Regex("(\\d{1,2})월(\\d{1,2})일").find(t)?.let { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            val candidate = runCatching { LocalDate.of(now.year, month, day) }.getOrNull()
            if (candidate != null) {
                val date = if (candidate.isAfter(now)) candidate.minusYears(1) else candidate
                return SpokenDate(date, SpokenDate.Kind.EXPLICIT)
            }
        }
        Regex("(\\d{1,3})일전").find(t)?.let { m ->
            return SpokenDate(now.minusDays(m.groupValues[1].toLong()), SpokenDate.Kind.RELATIVE)
        }
        return when {
            t.contains("그저께") || t.contains("그제") -> SpokenDate(now.minusDays(2), SpokenDate.Kind.RELATIVE)
            t.contains("어제") -> SpokenDate(now.minusDays(1), SpokenDate.Kind.RELATIVE)
            t.contains("지난주") || t.contains("저번주") -> SpokenDate(now.minusWeeks(1), SpokenDate.Kind.APPROXIMATE)
            t.contains("지난달") || t.contains("저번달") -> SpokenDate(now.minusMonths(1), SpokenDate.Kind.APPROXIMATE)
            t.contains("오늘") || t.contains("방금") -> SpokenDate(now, SpokenDate.Kind.TODAY)
            else -> SpokenDate(now, SpokenDate.Kind.UNSPOKEN)
        }
    }

    companion object {
        private val MAINTENANCE_VERB = Regex("수리|정비|교환|교체|점검|갈았")
        private val CHECK_VERB = Regex("점검|확인|봤|검사")
        private val NATIVE_DIGITS = mapOf(
            '일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5,
            '육' to 6, '칠' to 7, '팔' to 8, '구' to 9,
        )
        private val SMALL_UNITS = mapOf('십' to 10, '백' to 100, '천' to 1000)
        private const val UNIT_CHARS = "십백천만"

        /** 텍스트에 나온 원 단위 금액을 순서대로 돌려준다. "8만 원", "8만5천원", "80,000원", "만 원" */
        fun amounts(text: String): List<Long> {
            val result = mutableListOf<Long>()
            var idx = text.indexOf('원')
            while (idx >= 0) {
                amountBefore(text, idx)?.let(result::add)
                idx = text.indexOf('원', idx + 1)
            }
            return result
        }

        private fun isNumberChar(c: Char) =
            c.isDigit() || c == ',' || c == '.' || c in NATIVE_DIGITS || c in SMALL_UNITS || c == '만'

        /** '원' 바로 앞의 숫자 덩어리를 읽는다. 앞쪽 토큰은 숫자나 단위가 있을 때만 이어 붙인다 */
        private fun amountBefore(text: String, wonIndex: Int): Long? {
            val tokens = ArrayDeque<String>()
            var i = wonIndex - 1
            while (i >= 0) {
                while (i >= 0 && text[i] == ' ') i--
                val end = i + 1
                while (i >= 0 && isNumberChar(text[i])) i--
                if (end - (i + 1) == 0) break
                val token = text.substring(i + 1, end)
                // 단어 일부가 붙어 들어오면 중단한다: "엔진오일 팔만 원"의 "오일"
                val wordBoundaryOk = i < 0 || text[i] == ' '
                val first = tokens.isEmpty()
                val numericLooking = token.any { it.isDigit() || it in UNIT_CHARS }
                if (!first && (!numericLooking || !wordBoundaryOk)) break
                tokens.addFirst(if (wordBoundaryOk) token else trimToDigits(token) ?: break)
                if (!wordBoundaryOk) break
            }
            if (tokens.isEmpty()) return null
            return parseNumber(tokens.joinToString("").replace(",", ""))
        }

        /** "일8만"처럼 단어 끝 글자가 붙은 경우 첫 아라비아 숫자부터 읽는다 */
        private fun trimToDigits(token: String): String? {
            val firstDigit = token.indexOfFirst { it.isDigit() }
            return if (firstDigit >= 0) token.substring(firstDigit) else null
        }

        fun parseNumber(s: String): Long? {
            if (s.isEmpty()) return null
            var total = 0.0
            var section = 0.0
            var current: Double? = null
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isDigit() -> {
                        val start = i
                        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                        current = s.substring(start, i).toDoubleOrNull() ?: return null
                        continue
                    }
                    c in NATIVE_DIGITS -> current = NATIVE_DIGITS.getValue(c).toDouble()
                    c in SMALL_UNITS -> {
                        section += (current ?: 1.0) * SMALL_UNITS.getValue(c)
                        current = null
                    }
                    c == '만' -> {
                        val group = section + (current ?: 0.0)
                        total += (if (group == 0.0) 1.0 else group) * 10_000
                        section = 0.0
                        current = null
                    }
                    else -> return null
                }
                i++
            }
            val value = total + section + (current ?: 0.0)
            return if (value > 0) value.roundToLong() else null
        }
    }
}
