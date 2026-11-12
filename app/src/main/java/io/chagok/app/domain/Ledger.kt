package io.chagok.app.domain

import java.time.YearMonth

data class MonthLedger(
    val month: YearMonth,
    val total: Long,
    val byItem: Map<Item, Long>,
    /** 확정됐지만 금액이 없는 기록 수. 0원으로 합산하지 않는다 */
    val missingCostCount: Int,
    val entryCount: Int,
)

/** 기획서 5장 '비용 합계': 확정·유효한 기록의 금액만 해당 거래일·통화·항목에 합산 */
object Ledger {
    fun month(entries: List<DiaryEntry>, month: YearMonth, currency: String = "KRW"): MonthLedger {
        val inMonth = entries.filter {
            it.status == EntryStatus.CONFIRMED && it.currency == currency && YearMonth.from(it.performedOn) == month
        }
        val priced = inMonth.filter { it.cost != null }
        val byItem = priced.groupBy { it.item }.mapValues { (_, list) -> list.sumOf { it.cost!! } }
        return MonthLedger(
            month = month,
            total = priced.sumOf { it.cost!! },
            byItem = byItem,
            missingCostCount = inMonth.size - priced.size,
            entryCount = inMonth.size,
        )
    }

    fun months(entries: List<DiaryEntry>): List<YearMonth> =
        entries.filter { it.status == EntryStatus.CONFIRMED }
            .map { YearMonth.from(it.performedOn) }
            .distinct()
            .sortedDescending()
}
