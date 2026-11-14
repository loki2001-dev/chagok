package io.chagok.app.domain

import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.voice.DateKind
import ai.chagok.core.voice.KoreanNumber
import ai.chagok.core.voice.VoiceIntent
import java.time.LocalDate
import ai.chagok.core.voice.UtteranceParser as CoreParser

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
 *
 * 해석 규칙은 `:core`(ai.chagok.core.voice.UtteranceParser)에 있다. 이 클래스는 core의 결과를
 * 앱의 항목 체계([Item])로 옮기는 얇은 연결층이며, 공개 API는 이전과 같다.
 */
class UtteranceParser(private val today: () -> LocalDate) {

    fun parse(raw: String): SpokenIntent = when (val intent = CoreParser.parse(raw, today())) {
        is VoiceIntent.Draft -> {
            val item = if (Regex("냉각수|부동액").containsMatchIn(raw) && Regex("교체|교환|갈았|갈아|바꿨|바꾸").containsMatchIn(raw)) Item.COOLANT else itemOf(intent)
            SpokenIntent.Record(
                item = item,
                date = SpokenDate(intent.date.date, kindOf(intent.date.kind)),
                cost = intent.cost,
                wheels = if (item == Item.TIRE_REPLACE) intent.wheels.map { Wheel.valueOf(it.name) }.toSet() else emptySet(),
                memo = if (item == Item.OTHER || item == Item.SYMPTOM_NOTE) intent.memo ?: normalized(raw) else null,
            )
        }
        is VoiceIntent.CorrectCost -> SpokenIntent.CorrectCost(intent.newAmount)
        VoiceIntent.CancelDraft -> SpokenIntent.Cancel
        VoiceIntent.QuerySchedule -> SpokenIntent.QueryNext
        is VoiceIntent.Unrecognized -> SpokenIntent.Unknown(raw)
    }

    companion object {
        /** 텍스트에 나온 원 단위 금액을 순서대로 돌려준다. "8만 원", "8만5천원", "80,000원", "만 원", "1.5만 원" */
        fun amounts(text: String): List<Long> = CoreParser.amounts(text)

        fun serviceMileage(raw: String): Double? {
            val answer = raw.lowercase().trim().trimEnd('.', '!', '?')
            // "4만 킬로로 기억해요", "4만 킬로 정도요" are answers too.
            val match = Regex("""(?:약\s*)?([0-9일이삼사오육칠팔구십백천만영공.,\s]+?)\s*(?:km|킬로미터|킬로|키로미터|키로)?\s*(?:쯤|정도)?\s*(?:(?:으로|로)\s*기억(?:해|나)?요?|입니다|였어요|이었어요|이에요|예요|요)?""")
                .matchEntire(answer) ?: return null
            val number = match.groupValues[1].replace(Regex("""\s+"""), "")
            if (',' in number && !Regex("""\d{1,3}(,\d{3})+(\.\d+)?""").matches(number)) return null
            val compact = number.replace(",", "")
            if (compact == "영" || compact == "공") return 0.0
            if (compact.all { it.isDigit() || it == '.' }) return compact.toDoubleOrNull()?.takeIf { it.isFinite() }
            return parseNumber(compact)?.toDouble()
        }

        fun parseNumber(s: String): Long? = KoreanNumber.parse(s)?.takeIf { it > 0 }

        private fun normalized(raw: String) = raw.trim().replace(Regex("[.!?…]+"), " ").replace(Regex("\\s+"), " ").trim()

        private fun kindOf(k: DateKind): SpokenDate.Kind = when (k) {
            DateKind.UNSPOKEN -> SpokenDate.Kind.UNSPOKEN
            DateKind.TODAY -> SpokenDate.Kind.TODAY
            DateKind.RELATIVE -> SpokenDate.Kind.RELATIVE
            DateKind.APPROXIMATE -> SpokenDate.Kind.APPROXIMATE
            DateKind.EXPLICIT -> SpokenDate.Kind.EXPLICIT
        }

        /**
         * core의 (기록 종류, 항목)을 앱 항목으로 옮긴다. 앱 항목은 "점검"과 "교체"를 별도 항목으로 두므로 기록 종류를 함께 본다.
         * 앱에 대응 항목이 없으면 기타 정비로 두고 원문을 메모로 남긴다(예: "엔진오일 점검했어").
         */
        internal fun itemOf(d: VoiceIntent.Draft): Item = when (d.type) {
            EntryType.FUEL -> Item.REFUEL
            EntryType.CHARGE -> Item.CHARGE
            EntryType.SYMPTOM_MEMO -> Item.SYMPTOM_NOTE
            EntryType.REFILL -> if (d.item == MaintenanceItem.WASHER_FLUID) Item.WASHER_REFILL else Item.OTHER
            EntryType.INSPECTION -> when (d.item) {
                MaintenanceItem.BRAKE_PAD -> Item.BRAKE_PAD_CHECK
                MaintenanceItem.BATTERY_12V -> Item.BATTERY_12V_CHECK
                MaintenanceItem.WIPER -> Item.WIPER_CHECK
                else -> Item.OTHER
            }
            EntryType.MAINTENANCE -> when (d.item) {
                MaintenanceItem.ENGINE_OIL -> Item.ENGINE_OIL
                MaintenanceItem.DRIVE_BELT -> Item.DRIVE_BELT
                MaintenanceItem.TRANSMISSION_OIL -> Item.TRANSMISSION_OIL
                MaintenanceItem.BRAKE_FLUID -> Item.BRAKE_FLUID
                MaintenanceItem.SPARK_PLUG -> Item.SPARK_PLUG
                MaintenanceItem.IGNITION_COIL -> Item.IGNITION_COIL
                MaintenanceItem.CABIN_FILTER -> Item.CABIN_FILTER
                MaintenanceItem.TIRE_ROTATION -> Item.TIRE_ROTATION
                MaintenanceItem.TIRE_REPLACEMENT -> Item.TIRE_REPLACE
                MaintenanceItem.BATTERY_12V -> Item.BATTERY_12V_REPLACE
                MaintenanceItem.BRAKE_PAD -> Item.BRAKE_PAD_REPLACE
                MaintenanceItem.WIPER -> Item.WIPER_REPLACE
                MaintenanceItem.WASHER_FLUID -> Item.WASHER_REFILL
                MaintenanceItem.OTHER, null -> Item.OTHER
            }
        }
    }
}
