package ai.chagok.core.ledger

import ai.chagok.core.diary.DiaryEntry
import ai.chagok.core.diary.EntryType
import java.time.YearMonth

data class LedgerLine(val month: YearMonth, val category: String, val currency: String, val total: Long, val count: Int)

/** missingCostCount: 확정됐지만 금액이 미입력인 기록 수. 0원으로 합산하지 않고 따로 보여 준다. */
data class LedgerSummary(val lines: List<LedgerLine>, val missingCostCount: Int) {
    fun total(currency: String = "KRW"): Long = lines.filter { it.currency == currency }.sumOf { it.total }
    fun total(month: YearMonth, currency: String = "KRW"): Long = lines.filter { it.month == month && it.currency == currency }.sumOf { it.total }
    fun byCategory(currency: String = "KRW"): Map<String, Long> =
        lines.filter { it.currency == currency }.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.total } }
}

/**
 * 차계부. 확정된 기록의 금액만 거래일(실제 수행일)이 속한 달·항목·통화별로 합산한다.
 * 초안·후보·취소는 제외하고, 통화가 다르면 섞지 않는다. 언제든 일지에서 다시 계산하므로 수정·삭제가 바로 반영된다.
 */
object CostLedger {
    fun summarize(entries: List<DiaryEntry>, vehicleId: String, month: YearMonth? = null): LedgerSummary {
        val valid = entries.filter {
            it.vehicleId == vehicleId && it.isConfirmed && it.type != EntryType.SYMPTOM_MEMO &&
                (month == null || YearMonth.from(it.performedOn) == month)
        }
        val lines = valid.filter { it.cost != null }
            .groupBy { Triple(YearMonth.from(it.performedOn), it.category, it.cost!!.currency) }
            .map { (k, v) -> LedgerLine(k.first, k.second, k.third, v.sumOf { it.cost!!.amount }, v.size) }
            .sortedWith(compareBy({ it.month }, { it.category }, { it.currency }))
        return LedgerSummary(lines, valid.count { it.cost == null })
    }
}
