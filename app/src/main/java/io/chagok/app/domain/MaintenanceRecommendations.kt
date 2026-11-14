package io.chagok.app.domain

import java.time.LocalDate
import java.util.Locale

/** Intervals from the consumables chart. Start reminders at the lower end of a range. */
data class RecommendedInterval(
    val km: Int, val months: Int? = null, val maxKm: Int? = null, val maxMonths: Int? = null,
) {
    fun label(): String {
        val distance = listOfNotNull(km, maxKm).joinToString("~") { "%,d".format(Locale.KOREA, it) } + "km"
        val period = listOfNotNull(months, maxMonths).joinToString("~") {
            if (it % 12 == 0) "${it / 12}년" else "${it}개월"
        }
        return listOf(distance, period).filter(String::isNotEmpty).joinToString(" 또는 ")
    }
}

data class MaintenanceRecommendation(
    val recurring: RecommendedInterval, val initial: RecommendedInterval? = null, val note: String? = null,
) {
    fun plan(vehicleId: String, item: Item, today: LocalDate) =
        MaintenancePlan(vehicleId, item, recurring.km, recurring.months, "소모품 권장 주기표", today,
            initialKm = initial?.km)

    fun description() = listOfNotNull(initial?.let { "최초 ${it.label()}" },
        (if (initial == null) "" else "이후 ") + recurring.label(), note).joinToString(" · ")

    companion object {
        val byItem = mapOf(
            Item.ENGINE_OIL to MaintenanceRecommendation(RecommendedInterval(7_000, 6, 10_000, 12), note = "오일 필터 포함"),
            Item.CABIN_FILTER to MaintenanceRecommendation(RecommendedInterval(10_000, 6), note = "에어컨·히터 필터"),
            Item.BRAKE_PAD_CHECK to MaintenanceRecommendation(RecommendedInterval(30_000, maxKm = 40_000), note = "점검 후 교체"),
            Item.BRAKE_FLUID to MaintenanceRecommendation(RecommendedInterval(40_000, 24)),
            Item.TIRE_REPLACE to MaintenanceRecommendation(RecommendedInterval(40_000, 36, 50_000, 60)),
            Item.TRANSMISSION_OIL to MaintenanceRecommendation(RecommendedInterval(80_000, maxKm = 100_000), note = "점검 후 교체"),
            Item.BATTERY_12V_REPLACE to MaintenanceRecommendation(RecommendedInterval(50_000, 36, maxMonths = 60)),
            Item.COOLANT to MaintenanceRecommendation(RecommendedInterval(40_000, 24), initial = RecommendedInterval(200_000)),
            Item.SPARK_PLUG to MaintenanceRecommendation(RecommendedInterval(40_000, maxKm = 100_000), note = "플러그 소재에 따라 다름"),
        )
    }
}
