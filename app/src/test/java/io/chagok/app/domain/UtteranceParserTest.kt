package io.chagok.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 기획서 7장 발화 사전 */
class UtteranceParserTest {
    private val today = LocalDate.of(2026, 11, 8)
    private val parser = UtteranceParser { today }

    private fun record(text: String) = parser.parse(text) as SpokenIntent.Record

    @Test
    fun `오늘 엔진오일 갈았어 8만 원`() {
        val r = record("오늘 엔진오일 갈았어. 8만 원")
        assertEquals(Item.ENGINE_OIL, r.item)
        assertEquals(today, r.date.date)
        assertEquals(SpokenDate.Kind.TODAY, r.date.kind)
        assertEquals(80_000L, r.cost)
    }

    @Test
    fun `타이어 위치 교환은 교체와 구분한다`() {
        assertEquals(Item.TIRE_ROTATION, record("타이어 위치 교환했어").item)
    }

    @Test
    fun `앞 타이어 두 개 교체는 앞 두 바퀴`() {
        val r = record("앞 타이어 두 개 교체했어")
        assertEquals(Item.TIRE_REPLACE, r.item)
        assertEquals(setOf(Wheel.FRONT_LEFT, Wheel.FRONT_RIGHT), r.wheels)
    }

    @Test
    fun `지난주는 대략 날짜로 표시한다`() {
        val r = record("지난주에 오일 갈았어")
        assertEquals(Item.ENGINE_OIL, r.item)
        assertEquals(SpokenDate.Kind.APPROXIMATE, r.date.kind)
        assertEquals(today.minusWeeks(1), r.date.date)
    }

    @Test
    fun `주유했어 5만 원`() {
        val r = record("주유했어. 5만 원")
        assertEquals(Item.REFUEL, r.item)
        assertEquals(50_000L, r.cost)
        assertEquals(SpokenDate.Kind.UNSPOKEN, r.date.kind)
    }

    @Test
    fun `워셔액 채웠어는 보충 기록`() {
        assertEquals(Item.WASHER_REFILL, record("워셔액 채웠어").item)
    }

    @Test
    fun `금액 정정`() {
        assertEquals(SpokenIntent.CorrectCost(70_000), parser.parse("8만 원이 아니라 7만 원"))
        assertEquals(SpokenIntent.CorrectCost(65_000), parser.parse("6만5천원으로 바꿔"))
    }

    @Test
    fun `취소와 다음 관리 조회`() {
        assertEquals(SpokenIntent.Cancel, parser.parse("취소해"))
        assertEquals(SpokenIntent.QueryNext, parser.parse("다음 관리는 언제야?"))
        assertEquals(SpokenIntent.QueryNext, parser.parse("다음 엔진오일 교환은 언제야"))
    }

    @Test
    fun `과거 날짜 표현`() {
        assertEquals(today.minusDays(1), record("어제 와이퍼 점검했어").date.date)
        assertEquals(today.minusDays(3), record("3일 전에 엔진오일 교환").date.date)
        val explicit = record("11월 3일에 오일 갈았어")
        assertEquals(LocalDate.of(2026, 11, 3), explicit.date.date)
        // 오늘보다 뒤의 월일은 작년으로 본다
        assertEquals(LocalDate.of(2025, 12, 20), record("12월 20일 오일 교환").date.date)
    }

    @Test
    fun `증상 메모`() {
        val r = record("브레이크 밟을 때 끼익 소리가 나")
        assertEquals(Item.SYMPTOM_NOTE, r.item)
        assertTrue(r.memo!!.contains("소리"))
    }

    @Test
    fun `알아듣지 못한 말은 저장하지 않는다`() {
        assertTrue(parser.parse("음악 틀어줘") is SpokenIntent.Unknown)
        assertTrue(parser.parse("   ") is SpokenIntent.Unknown)
    }

    @Test
    fun `금액 표현`() {
        val a = { s: String -> UtteranceParser.amounts(s).firstOrNull() }
        assertEquals(80_000L, a("8만 원"))
        assertEquals(80_000L, a("80,000원"))
        assertEquals(85_000L, a("8만5천원"))
        assertEquals(85_000L, a("8만 5천 원"))
        assertEquals(10_000L, a("만 원"))
        assertEquals(20_000L, a("이만 원"))
        assertEquals(80_000L, a("엔진오일 팔만원"))
        assertEquals(80_000L, a("엔진오일8만원"))
        assertEquals(15_000L, a("1.5만 원"))
        assertNull(a("공원에 다녀왔어"))
        assertNull(a("엔진오일 갈았어"))
    }
}
