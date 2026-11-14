package ai.chagok.core

import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.diary.Wheel
import ai.chagok.core.voice.DateExpression
import ai.chagok.core.voice.DateKind
import ai.chagok.core.voice.KoreanNumber
import ai.chagok.core.voice.PleosSttErrors
import ai.chagok.core.voice.UtteranceParser
import ai.chagok.core.voice.VoiceFailure
import ai.chagok.core.voice.VoiceIntent
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 기획서 07 발화 사전을 한 줄씩 그대로 검증한다. */
class UtteranceParserTest {
    private val today = Fx.TODAY
    private fun draft(t: String) = assertIs<VoiceIntent.Draft>(UtteranceParser.parse(t, today), t)

    @Test fun engineOilTodayWithCost() {
        val d = draft("오늘 엔진오일 갈았어. 8만 원")
        assertEquals(EntryType.MAINTENANCE, d.type); assertEquals(MaintenanceItem.ENGINE_OIL, d.item)
        assertEquals(today, d.date.date); assertTrue(d.date.stated && !d.date.approximate); assertEquals(80_000L, d.cost)
    }

    @Test fun tireRotationIsNotReplacement() {
        val d = draft("타이어 위치 교환했어")
        assertEquals(MaintenanceItem.TIRE_ROTATION, d.item); assertTrue(d.wheels.isEmpty()); assertEquals(null, d.cost)
    }

    @Test fun frontTwoTiresReplaced() {
        val d = draft("앞 타이어 두 개 교체했어")
        assertEquals(MaintenanceItem.TIRE_REPLACEMENT, d.item); assertEquals(Wheel.FRONT, d.wheels); assertEquals(2, d.wheelCount)
        assertEquals(setOf(Wheel.FRONT_LEFT), draft("운전석 앞 타이어 교체했어").wheels)
        val all = draft("타이어 네 개 다 갈았어 40만 원")
        assertEquals(Wheel.ALL, all.wheels); assertEquals(400_000L, all.cost)
        assertTrue(draft("타이어 교체했어").wheels.isEmpty(), "위치를 안 말하면 확인 화면에서 묻는다")
    }

    @Test fun lastWeekIsApproximatePastDate() {
        val d = draft("지난주에 오일 갈았어")
        assertEquals(MaintenanceItem.ENGINE_OIL, d.item); assertEquals(today.minusWeeks(1), d.date.date); assertTrue(d.date.approximate)
    }

    @Test fun refuelWithCost() {
        val d = draft("주유했어. 5만 원")
        assertEquals(EntryType.FUEL, d.type); assertEquals(null, d.item); assertEquals(50_000L, d.cost)
        assertEquals(EntryType.FUEL, draft("기름 넣었어").type)
    }

    @Test fun washerFluidIsUserRefill() {
        val d = draft("워셔액 채웠어")
        assertEquals(EntryType.REFILL, d.type); assertEquals(MaintenanceItem.WASHER_FLUID, d.item)
    }

    @Test fun costCorrection() {
        assertEquals(VoiceIntent.CorrectCost(70_000, 80_000), UtteranceParser.parse("8만 원이 아니라 7만 원", today))
        assertEquals(VoiceIntent.CorrectCost(70_000, null), UtteranceParser.parse("7만 원으로 고쳐줘", today))
    }

    @Test fun cancelAndScheduleQuery() {
        assertEquals(VoiceIntent.CancelDraft, UtteranceParser.parse("취소해", today))
        assertEquals(VoiceIntent.QuerySchedule, UtteranceParser.parse("다음 관리는 언제야?", today))
        assertEquals(VoiceIntent.QuerySchedule, UtteranceParser.parse("엔진오일 언제 갈아야 해?", today))
    }

    @Test fun otherTypes() {
        val memo = draft("브레이크 밟을 때 끼익 소리 나")
        assertEquals(EntryType.SYMPTOM_MEMO, memo.type); assertEquals("브레이크 밟을 때 끼익 소리 나", memo.memo)
        val insp = draft("브레이크 패드 점검했어")
        assertEquals(EntryType.INSPECTION, insp.type); assertEquals(MaintenanceItem.BRAKE_PAD, insp.item)
        assertEquals(EntryType.CHARGE, draft("배터리 충전했어").type)
        assertEquals(EntryType.MAINTENANCE, draft("배터리 교체했어 12만 원").type)
        assertEquals(MaintenanceItem.CABIN_FILTER, draft("에어컨 필터 바꿨어").item)
        assertIs<VoiceIntent.Unrecognized>(UtteranceParser.parse("음 그러니까", today))
        assertIs<VoiceIntent.Unrecognized>(UtteranceParser.parse("   ", today))
    }

    @Test fun moreMaintenanceItemsBeforeEngineOil() {
        mapOf(
            "드라이브 벨트 교체했어" to MaintenanceItem.DRIVE_BELT,
            "변속기 오일 갈았어" to MaintenanceItem.TRANSMISSION_OIL,
            "미션오일 교환했어" to MaintenanceItem.TRANSMISSION_OIL,
            "브레이크 오일 교환했어" to MaintenanceItem.BRAKE_FLUID,
            "점화플러그 교체했어" to MaintenanceItem.SPARK_PLUG,
            "점화코일 교체했어" to MaintenanceItem.IGNITION_COIL,
            "에어컨 필터 교체했어" to MaintenanceItem.CABIN_FILTER,
            "12V 배터리 교체했어" to MaintenanceItem.BATTERY_12V,
        ).forEach { (text, item) -> val d = draft(text); assertEquals(item, d.item, text); assertEquals(EntryType.MAINTENANCE, d.type, text) }
        assertEquals(MaintenanceItem.ENGINE_OIL, draft("엔진오일 갈았어").item, "구체적인 오일 이름이 아니면 엔진오일")
    }

    @Test fun unknownItemWithServiceWordOrAmountIsOtherMaintenance() {
        val d = draft("정비소에서 하체 수리했어 25만 원")
        assertEquals(MaintenanceItem.OTHER, d.item); assertEquals(250_000L, d.cost); assertEquals("정비소에서 하체 수리했어 25만 원", d.memo)
        assertEquals(MaintenanceItem.OTHER, draft("5만 원").item, "금액만 말해도 기타 정비 초안")
        assertIs<VoiceIntent.Unrecognized>(UtteranceParser.parse("음악 틀어줘", today))
    }

    @Test fun anyWhenQuestionIsAScheduleQuery() {
        assertEquals(VoiceIntent.QuerySchedule, UtteranceParser.parse("다음 엔진오일 교환은 언제야", today))
    }

    @Test fun dateKinds() {
        assertEquals(DateKind.TODAY, draft("오늘 엔진오일 갈았어").date.kind)
        assertEquals(DateKind.UNSPOKEN, draft("주유했어 5만 원").date.kind)
        assertEquals(DateKind.RELATIVE, draft("어제 와이퍼 점검했어").date.kind)
        assertEquals(DateKind.APPROXIMATE, draft("지난주에 오일 갈았어").date.kind)
        assertEquals(DateKind.EXPLICIT, draft("11월 3일에 오일 갈았어").date.kind)
        assertEquals(DateKind.RELATIVE, ai.chagok.core.voice.DateGuess(today, true, false).kind, "직접 만들면 stated·approximate에서 정한다")
    }

    @Test fun frontAndRearMeansAllWheels() {
        assertEquals(Wheel.ALL, draft("앞뒤 타이어 교체했어").wheels)
    }

    @Test fun koreanAmounts() {
        assertEquals(15_000L, KoreanNumber.parse("1.5만"))
        assertEquals(listOf(15_000L), UtteranceParser.amounts("1.5만 원"))
        assertEquals(listOf(80_000L), UtteranceParser.amounts("엔진오일8만원"))
        assertEquals(listOf(20_000L), UtteranceParser.amounts("이만 원"))
        assertEquals(emptyList(), UtteranceParser.amounts("공원에 다녀왔어"))
        assertEquals(80_000L, KoreanNumber.parse("80,000"))
        assertEquals(85_000L, KoreanNumber.parse("8만 5천"))
        assertEquals(70_000L, KoreanNumber.parse("칠만"))
        assertEquals(15_000L, KoreanNumber.parse("만오천"))
        assertEquals(12_500L, KoreanNumber.parse("1만2천5백"))
        assertEquals(null, KoreanNumber.parse("만원짜리"))
        assertEquals(listOf(80_000L), UtteranceParser.amounts("엔진오일 팔만 원"), "항목 이름의 '오·일'을 숫자로 읽지 않는다")
        assertEquals(listOf(10_000L), UtteranceParser.amounts("와이퍼 만 원"))
    }

    @Test fun dateExpressions() {
        assertEquals(today.minusDays(1), DateExpression.parse("어제 갈았어", today).date)
        assertEquals(today.minusDays(2), DateExpression.parse("그저께", today).date)
        assertEquals(today.minusDays(3), DateExpression.parse("3일 전에", today).date)
        assertEquals(LocalDate.of(2026, 11, 3), DateExpression.parse("11월 3일에 했어", today).date)
        assertEquals(LocalDate.of(2025, 12, 25), DateExpression.parse("12월 25일", today).date, "미래 날짜는 작년으로")
        assertTrue(DateExpression.parse("지난달에", today).approximate)
        val none = DateExpression.parse("엔진오일 갈았어", today)
        assertEquals(today, none.date); assertTrue(!none.stated)
    }

    @Test fun pleosSttErrorMapping() {
        assertEquals(VoiceFailure.PERMISSION_DENIED, PleosSttErrors.map("PERMISSION_DENIED"))
        assertEquals(VoiceFailure.NO_SPEECH, PleosSttErrors.map("TIMEOUT"))
        assertEquals(VoiceFailure.ENGINE_UNAVAILABLE, PleosSttErrors.map("ON_DEVICE_ENGINE_ERROR"))
        assertEquals(VoiceFailure.LANGUAGE_UNAVAILABLE, PleosSttErrors.map("UNAVAILABLE_LANGUAGE"))
        assertEquals(VoiceFailure.NETWORK, PleosSttErrors.map("NETWORK_ERROR"))
        assertEquals(VoiceFailure.OTHER, PleosSttErrors.map("SOMETHING_ADDED_LATER"), "새 값이 생겨도 OTHER로 받는다")
    }
}
