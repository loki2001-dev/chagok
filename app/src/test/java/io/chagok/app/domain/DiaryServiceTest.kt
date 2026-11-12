package io.chagok.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 기획서 11장 '일지 정확성', '감지 후보', '학습' */
class DiaryServiceTest {
    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.ofHours(9)
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = instant
        fun advance(d: Duration) {
            instant = instant.plus(d)
        }
    }

    private val clock = MutableClock(Instant.parse("2026-11-08T01:00:00Z")) // 2026-11-08 10:00 KST
    private val store = MemoryStore()
    private var seq = 0
    private val service = DiaryService(store, clock) { "id${++seq}" }

    private fun odo(km: Double, ago: Duration = Duration.ZERO) =
        Reading.Value(Observation(km, clock.instant().minus(ago)))

    private fun voice(text: String, reading: Reading = odo(47_200.0), author: String = "나", active: String? = null) =
        service.handleVoice(text, reading, author, active)

    private fun drafted(text: String, reading: Reading = odo(47_200.0), author: String = "나") =
        voice(text, reading, author) as DiaryService.VoiceOutcome.Drafted

    private fun saved(id: String) = service.confirm(id) as DiaryService.ConfirmResult.Saved

    @Test
    fun `대표 시연 - 말하기, 확인, 일정 갱신, 도감`() {
        service.setPlan(Item.ENGINE_OIL, 10_000, 12, "시연용 예시값")
        val d = drafted("오늘 엔진오일 갈았어. 8만 원")
        assertEquals(EntryStatus.DRAFT, d.draft.status)
        assertEquals(Source.VEHICLE, d.draft.odometer!!.source)
        assertEquals(47_200.0, d.draft.odometer!!.value, 0.0)
        // 확인 전에는 비용과 일정에 반영되지 않는다
        assertEquals(0L, service.ledger(java.time.YearMonth.of(2026, 11)).total)
        assertEquals(ScheduleState.NO_RECORD, service.schedules(47_200.0).single().state)

        saved(d.draft.id)
        assertEquals(80_000L, service.ledger(java.time.YearMonth.of(2026, 11)).total)
        val s = service.schedules(47_200.0).single()
        assertEquals(57_200.0, s.nextKm!!, 0.0)
        assertEquals(java.time.LocalDate.of(2027, 11, 8), s.nextDate)

        val missions = service.missions("나", 47_200.0)
        assertEquals(listOf(Topic.FIRST_DIARY, Topic.READ_INTERVAL), missions.map { it.topic })
        missions.forEach { m ->
            val wrong = service.answer("나", m, (m.answerIndex + 1) % m.choices.size)
            assertFalse(wrong.correct)
            assertNotNull(wrong.hint)
            assertTrue(service.answer("나", m, m.answerIndex).stamped)
        }
        assertEquals(2, service.stamps("나").size)
        // 같은 주제는 도장을 다시 주지 않는다
        assertFalse(service.answer("나", missions[0], missions[0].answerIndex).stamped)
        assertTrue(service.missions("나", 47_200.0).isEmpty())
    }

    @Test
    fun `과거 정비에는 오늘 km를 붙이지 않는다`() {
        val d = drafted("지난주에 오일 갈았어")
        assertNull(d.draft.odometer)
        assertTrue(d.checks.any { it.contains("당시 주행거리") && it.contains("참고값") })
    }

    @Test
    fun `km 미수신과 오래된 값은 0으로 채우지 않는다`() {
        assertNull(drafted("엔진오일 교환 8만 원", Reading.Unavailable("미지원")).draft.odometer)
        assertNull(drafted("타이어 위치 교환", odo(47_000.0, ago = Duration.ofHours(1))).draft.odometer)
    }

    @Test
    fun `사용자가 km를 고치면 사용자 값이 되고 관측값은 남는다`() {
        val d = drafted("오늘 엔진오일 갈았어")
        val e = service.setOdometer(d.draft.id, 47_150.0)!!
        assertEquals(Source.USER, e.odometer!!.source)
        assertEquals(47_200.0, e.odometer!!.observed!!.value, 0.0)
    }

    @Test
    fun `음성 금액 정정과 초안 취소`() {
        val d = drafted("오늘 엔진오일 갈았어. 8만 원")
        val c = voice("8만 원이 아니라 7만 원", active = d.draft.id) as DiaryService.VoiceOutcome.Corrected
        assertEquals(70_000L, c.draft.cost)
        assertEquals(80_000L, c.previousCost)
        assertTrue(voice("취소해", active = d.draft.id) is DiaryService.VoiceOutcome.Cancelled)
        assertNull(service.entry(d.draft.id))
        // 대상이 없으면 묻는다
        assertTrue(voice("8만 원이 아니라 7만 원") is DiaryService.VoiceOutcome.Ask)
    }

    @Test
    fun `저장 후 수정은 이력을 남기고 합계를 다시 계산한다`() {
        val d = drafted("오늘 엔진오일 갈았어. 8만 원")
        saved(d.draft.id)
        val e = service.setCost(d.draft.id, 75_000)!!
        assertEquals(2, e.version)
        assertEquals(1, e.history.size)
        assertEquals(75_000L, service.ledger(java.time.YearMonth.of(2026, 11)).total)
    }

    @Test
    fun `중복 저장 경고`() {
        saved(drafted("오늘 엔진오일 갈았어").draft.id)
        val second = drafted("오늘 엔진오일 갈았어")
        assertTrue(service.confirm(second.draft.id) is DiaryService.ConfirmResult.PossibleDuplicate)
        assertTrue(service.confirm(second.draft.id, allowDuplicate = true) is DiaryService.ConfirmResult.Saved)
        // 이미 확정된 기록은 다시 확정하지 않는다
        assertTrue(service.confirm(second.draft.id) is DiaryService.ConfirmResult.Rejected)
    }

    @Test
    fun `EV 프로필에는 엔진오일이 없다`() {
        service.updateProfile("우리 차", Powertrain.EV, null)
        assertTrue(voice("오늘 엔진오일 갈았어") is DiaryService.VoiceOutcome.Ask)
        assertFalse(Item.ENGINE_OIL in service.items())
    }

    @Test
    fun `취소하면 비용·일정에서 빠지고 도장 근거를 다시 본다`() {
        service.setPlan(Item.ENGINE_OIL, 10_000, 12, "예시")
        val d = drafted("오늘 엔진오일 갈았어. 8만 원")
        saved(d.draft.id)
        val m = service.missions("나", 47_200.0).first()
        assertTrue(service.answer("나", m, m.answerIndex).stamped)

        service.cancel(d.draft.id)
        assertEquals(EntryStatus.CANCELLED, service.entry(d.draft.id)!!.status)
        assertEquals(0L, service.ledger(java.time.YearMonth.of(2026, 11)).total)
        assertEquals(ScheduleState.NO_RECORD, service.schedules(47_200.0).single().state)
        assertTrue(service.stamps("나").single().needsReview)
    }

    @Test
    fun `주유 후보 - 정차 중 안정된 상승만, 확인 후 확정`() {
        fun feed(v: Double?, parked: Boolean = true, step: Long = 15) = service.observeEnergy(EnergyKind.FUEL, v, parked).also {
            clock.advance(Duration.ofSeconds(step))
        }
        feed(30.0); assertNull(feed(30.0)) // 기준선 30%
        // 주행 중 상승(센서 출렁임)은 무시한다
        assertNull(feed(40.0, parked = false))
        feed(30.0); assertNull(feed(30.0))
        // 정차 중 80%로 올라 안정
        assertNull(feed(80.0))
        val c = feed(80.0)
        assertNotNull(c)
        assertEquals(CandidateState.PENDING, c!!.state)
        assertFalse(c.gapLong)
        // 후보는 비용 합계에 들어가지 않는다
        assertEquals(0, service.confirmedEntries().size)

        val d = drafted("주유했어. 5만 원")
        assertEquals(c.id, d.draft.candidateId)
        saved(d.draft.id)
        assertTrue(service.pendingCandidates().isEmpty())
        assertEquals(50_000L, service.ledger(java.time.YearMonth.of(2026, 11)).total)
    }

    @Test
    fun `수신 공백 뒤 상승은 마지막 관측 이후 증가로 설명한다`() {
        service.observeEnergy(EnergyKind.FUEL, 20.0, true); clock.advance(Duration.ofSeconds(15))
        service.observeEnergy(EnergyKind.FUEL, 20.0, true)
        clock.advance(Duration.ofHours(3))
        service.observeEnergy(EnergyKind.FUEL, 70.0, true); clock.advance(Duration.ofSeconds(15))
        val c = service.observeEnergy(EnergyKind.FUEL, 70.0, true)!!
        assertTrue(c.gapLong)
        assertTrue(CandidateRules.describe(c).startsWith("마지막 관측 이후"))
        service.excludeCandidate(c.id)
        assertTrue(service.pendingCandidates().isEmpty())
    }

    @Test
    fun `미수신 잔량은 무시한다`() {
        assertNull(service.observeEnergy(EnergyKind.FUEL, null, true))
        assertNull(service.observeEnergy(EnergyKind.SOC, 50.0, true)) // 내연기관 프로필
    }

    @Test
    fun `공용 일지 변경 사항`() {
        saved(drafted("오늘 엔진오일 갈았어", author = "엄마").draft.id)
        assertEquals(1, service.changesSince("나").size)
        assertTrue(service.changesSince("엄마").isEmpty())
        service.markSeen("나")
        clock.advance(Duration.ofMinutes(1))
        assertTrue(service.changesSince("나").isEmpty())
    }

    @Test
    fun `리포트에는 VIN을 넣지 않는다`() {
        service.attachVin("KMHXX00XXXX000000")
        saved(drafted("오늘 엔진오일 갈았어. 8만 원").draft.id)
        val r = service.report(java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31), null, true)
        assertTrue(r.contains("엔진오일 교환"))
        assertFalse(r.contains("KMH"))
        // 다른 VIN으로 덮어쓰지 않는다
        assertFalse(service.attachVin("OTHERVIN"))
    }
}
