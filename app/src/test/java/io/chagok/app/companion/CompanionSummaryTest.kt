package io.chagok.app.companion

import io.chagok.app.domain.DriveTrip
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CompanionSummaryTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 15)
    private val now = today.atStartOfDay(zone).toInstant()
    private fun trip(id: String, user: String = "나", at: Instant = now, distance: Double? = 12.0, gap: Boolean = false) =
        DriveTrip(id, "v", user, at, at.plusSeconds(120), startKm = 100.0, endKm = distance?.plus(100), hasGap = gap)

    @Test fun headlineCountsOnlyCurrentUsersTripsOnTheirLocalDay() {
        val trips = listOf(trip("today"), trip("other", "가족"), trip("yesterday", at = now.minusSeconds(1)))
        assertEquals("오늘 1회 운행 · 12.0km", CompanionSummary.today(trips, "나", today, zone))
    }

    @Test fun missingDistanceIsNotPresentedAsACompleteZeroKilometreTrip() {
        val text = CompanionSummary.today(listOf(trip("missing", distance = null)), "나", today, zone)
        assertTrue(text.contains("거리 확인 필요"))
        assertFalse(text.contains("0.0km"))
        val partial = CompanionSummary.today(listOf(trip("known"), trip("missing", distance = null)), "나", today, zone)
        assertTrue(partial.contains("일부 미수신"))
    }

    @Test fun emptyAndGapStatesMakeNoClaimOfSuccessfulCompleteRecording() {
        assertEquals("달리면 오늘의 운행이 자동으로 남아요", CompanionSummary.today(emptyList(), "나", today, zone))
        assertTrue(CompanionSummary.today(listOf(trip("gap", gap = true)), "나", today, zone).contains("일부 미수신"))
    }
}
