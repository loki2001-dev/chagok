package io.chagok.app.companion

import io.chagok.app.domain.DriveTrip
import java.time.LocalDate
import java.time.ZoneId

/** Only today's own observations appear on the shared vehicle screen. */
object CompanionSummary {
    fun today(trips: List<DriveTrip>, user: String, date: LocalDate, zone: ZoneId): String {
        val own = trips.filter { it.user == user && it.startedAt.atZone(zone).toLocalDate() == date }
        if (own.isEmpty()) return "달리면 오늘의 운행이 자동으로 남아요"
        val distances = own.mapNotNull { it.distanceKm }
        val distance = if (distances.isEmpty()) "거리 확인 필요" else "%.1fkm".format(distances.sum())
        val incomplete = own.any { it.hasGap } || distances.size != own.size
        return "오늘 ${own.size}회 운행 · $distance" + if (incomplete) " · 일부 미수신" else ""
    }
}
