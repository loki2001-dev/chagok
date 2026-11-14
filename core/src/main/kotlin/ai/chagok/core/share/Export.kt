package ai.chagok.core.share

import ai.chagok.core.diary.DiaryEntry
import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.diary.Money
import ai.chagok.core.ledger.CostLedger
import ai.chagok.core.ledger.LedgerSummary
import ai.chagok.core.model.EvidenceSource
import ai.chagok.core.model.Member
import ai.chagok.core.model.VehicleProfile
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 내보낼 범위. 사용자가 고른 날짜·항목·메모만 나간다. VIN과 구성원 이름은 기본 제외.
 * 증상 메모는 memoIds로 고른 것만 들어간다.
 */
data class ExportSpec(
    val vehicleId: String,
    val from: LocalDate,
    val to: LocalDate,
    val items: Set<MaintenanceItem>? = null,
    val types: Set<EntryType> = setOf(EntryType.MAINTENANCE, EntryType.INSPECTION, EntryType.REFILL, EntryType.FUEL, EntryType.CHARGE),
    val memoIds: Set<String> = emptySet(),
    val includeAuthors: Boolean = false,
    val includeVin: Boolean = false,
)

/** 한 줄. 주행거리 출처(차량 수신/사용자 입력)를 함께 둔다. 음성 기록을 정비소 인증 이력으로 표시하지 않는다. */
data class ExportRow(
    val entryId: String,
    val date: LocalDate,
    val type: EntryType,
    val category: String,
    val wheels: String?,
    val cost: Money?,
    val odometerKm: Double?,
    val odometerSource: EvidenceSource?,
    val note: String?,
    val author: String?,
)

data class ExportReport(
    val vehicleName: String,
    val modelName: String?,
    val vin: String?,
    val from: LocalDate,
    val to: LocalDate,
    val rows: List<ExportRow>,
    val ledger: LedgerSummary,
)

object ReportExporter {
    private val fmt = DateTimeFormatter.ofPattern("yyyy.MM.dd")

    fun build(spec: ExportSpec, vehicle: VehicleProfile, entries: List<DiaryEntry>, members: List<Member>): ExportReport {
        val inRange = entries.filter {
            it.vehicleId == spec.vehicleId && it.isConfirmed && !it.performedOn.isBefore(spec.from) && !it.performedOn.isAfter(spec.to)
        }
        val picked = inRange.filter { e ->
            if (e.type == EntryType.SYMPTOM_MEMO) e.id in spec.memoIds
            else e.type in spec.types && (spec.items == null || e.item == null || e.item in spec.items)
        }.sortedWith(compareBy({ it.performedOn }, { it.confirmedAt }))
        val names = members.associate { it.id to it.name }
        val rows = picked.map { e ->
            ExportRow(
                e.id, e.performedOn, e.type, e.category,
                e.wheels.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.label },
                e.cost, e.odometer?.value, e.odometer?.source,
                e.note, if (spec.includeAuthors) names[e.authorId] ?: e.authorId else null,
            )
        }
        val ledger = CostLedger.summarize(picked.filter { it.type != EntryType.SYMPTOM_MEMO }, spec.vehicleId)
        return ExportReport(vehicle.nickname, vehicle.modelName, if (spec.includeVin) vehicle.vin else null, spec.from, spec.to, rows, ledger)
    }

    fun render(r: ExportReport): String = buildString {
        appendLine("차량 일지 · ${r.vehicleName}${r.modelName?.let { " ($it)" } ?: ""}")
        r.vin?.let { appendLine("VIN $it") }
        appendLine("기간 ${r.from.format(fmt)} ~ ${r.to.format(fmt)} · ${r.rows.size}건 · 확정 비용 ${won(r.ledger.total())}" +
            if (r.ledger.missingCostCount > 0) " · 금액 미입력 ${r.ledger.missingCostCount}건" else "")
        for (row in r.rows) {
            val km = row.odometerKm?.let { "${"%,d".format(Math.round(it))}km(${row.odometerSource?.label})" } ?: "주행거리 없음"
            append("- ${row.date.format(fmt)} ${row.category}")
            row.wheels?.let { append(" [$it]") }
            append(" · $km · ${row.cost?.let { won(it.amount) } ?: "금액 미입력"}")
            row.note?.let { append(" · \"$it\"") }
            row.author?.let { append(" · $it") }
            appendLine()
        }
        appendLine("음성·수동으로 사용자가 기록한 일지이며 정비소 인증 이력이 아닙니다.")
    }

    private fun won(v: Long) = "%,d원".format(v)
}
