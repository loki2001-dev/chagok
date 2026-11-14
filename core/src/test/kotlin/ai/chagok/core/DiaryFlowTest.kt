package ai.chagok.core

import ai.chagok.core.diary.EntryStatus
import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.diary.Money
import ai.chagok.core.learning.Topic
import ai.chagok.core.ledger.CostLedger
import ai.chagok.core.model.EvidenceSource
import ai.chagok.core.model.Powertrain
import ai.chagok.core.schedule.DueState
import ai.chagok.core.share.OdometerState
import ai.chagok.core.voice.VoiceFailure
import ai.chagok.core.voice.VoiceState
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 기획서 대표 시연: "엔진오일 갈았어" → 날짜·km·비용 확인 → 일정 갱신 → 관리 도감. 그리고 예외 경로. */
class DiaryFlowTest {
    private fun ledger(h: Harness) = CostLedger.summarize(h.repo.entries(), Fx.CAR)

    @Test fun mainDemo() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0.minusSeconds(60)))
        h.say("오늘 엔진오일 갈았어. 8만 원")
        val d = h.last<EngineEvent.DraftReady>()
        with(d.entry) {
            assertEquals(EntryStatus.DRAFT, status); assertEquals(MaintenanceItem.ENGINE_OIL, item); assertEquals(Money(80_000), cost)
            assertEquals(47_200.0, odometer!!.value); assertEquals(EvidenceSource.VEHICLE, odometer!!.source)
            assertEquals(Fx.T0.minusSeconds(60), odometer!!.receivedAt, "주행거리는 수신 시각과 함께 붙는다")
            assertEquals("오늘 엔진오일 갈았어. 8만 원", utterance)
        }
        assertTrue(d.questions.isEmpty())
        assertEquals(VoiceState.DRAFT, h.engine.voice.value.state, "인식 완료는 저장 완료가 아니다")
        assertEquals(0L, ledger(h).total(), "초안은 비용에 반영하지 않는다")

        h.cmd(Command.SetPlan(Fx.CAR, MaintenanceItem.ENGINE_OIL, 10_000.0, 12, "사용자 확인: 차량 매뉴얼"))
        assertEquals(DueState.NO_BASE_RECORD, h.last<EngineEvent.ScheduleChanged>().statuses.single().state)

        h.cmd(Command.ConfirmEntry(d.entry.id, "minji"))
        assertEquals(EntryStatus.CONFIRMED, h.last<EngineEvent.EntryConfirmed>().entry.status)
        assertEquals(VoiceState.SAVED, h.engine.voice.value.state)
        val s = h.last<EngineEvent.ScheduleChanged>().statuses.single()
        assertEquals(57_200.0, s.nextKm); assertEquals(LocalDate.of(2027, 11, 8), s.nextDate); assertEquals(DueState.UPCOMING, s.state)
        assertEquals(80_000L, ledger(h).total())
        assertEquals(Topic.FIRST_DIARY, h.last<EngineEvent.LearningSuggested>().topic, "저장 뒤 관련 학습 하나를 권한다")
    }

    @Test fun pastEntryAsksForThenOdometerAndDate() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0))
        h.say("지난주에 오일 갈았어")
        val d = h.last<EngineEvent.DraftReady>()
        assertEquals(LocalDate.of(2026, 11, 1), d.entry.performedOn); assertTrue(d.entry.dateApproximate)
        assertNull(d.entry.odometer, "과거 정비에 오늘 km를 자동으로 붙이지 않는다")
        assertEquals(47_200.0, d.entry.referenceOdometer!!.value, "현재 km는 참고값으로만")
        assertEquals(setOf(DraftQuestion.CONFIRM_DATE, DraftQuestion.ASK_PAST_ODOMETER, DraftQuestion.ASK_COST), d.questions)

        h.cmd(Command.ConfirmEntry(d.entry.id, "minji"))
        assertEquals(RejectReason.DATE_NEEDS_CONFIRMATION, h.last<EngineEvent.Rejected>().reason)
        h.cmd(Command.EditEntry(d.entry.id, "minji", EntryEdit(performedOn = LocalDate.of(2026, 10, 30), odometerKm = 46_900.0)))
        val fixed = h.last<EngineEvent.DraftReady>()
        assertEquals(setOf(DraftQuestion.ASK_COST), fixed.questions)
        assertEquals(EvidenceSource.USER, fixed.entry.odometer!!.source); assertTrue(!fixed.entry.dateApproximate)
        h.cmd(Command.ConfirmEntry(d.entry.id, "minji"))
        assertEquals(LocalDate.of(2026, 10, 30), h.repo.entry(d.entry.id)!!.performedOn)
    }

    @Test fun voiceCostCorrectionBeforeAndAfterSaving() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(30_000.0, Fx.T0))
        h.say("오늘 타이어 위치 교환했어 5만 원")
        val id = h.last<EngineEvent.DraftReady>().entry.id
        h.say("5만 원이 아니라 4만 원")
        assertEquals(Money(40_000), h.last<EngineEvent.DraftReady>().entry.cost); assertEquals(id, h.last<EngineEvent.DraftReady>().entry.id)
        assertEquals(VoiceState.DRAFT, h.engine.voice.value.state)
        h.cmd(Command.ConfirmEntry(id, "minji"))
        h.say("4만 원이 아니라 3만 원")
        val up = h.last<EngineEvent.EntryUpdated>().entry
        assertEquals(2, up.version); assertEquals(Money(30_000), up.cost)
        assertEquals(listOf("cost" to ("40000" to "30000")), up.revisions.map { it.field to (it.before to it.after) }, "저장 후 수정은 이력을 남긴다")
        assertEquals(VoiceState.SAVED, h.engine.voice.value.state)
        assertEquals(30_000L, ledger(h).total(), "수정하면 합계를 다시 계산한다")
    }

    @Test fun cancelDraftByVoiceAndCancelledEntriesAreExcluded() = runTest {
        val h = Harness(this)
        h.say("와이퍼 갈았어 2만 원")
        val id = h.last<EngineEvent.DraftReady>().entry.id
        h.say("취소해")
        assertEquals(id, h.last<EngineEvent.EntryCancelled>().entry.id)
        assertEquals(EntryStatus.CANCELLED, h.repo.entry(id)!!.status); assertEquals(VoiceState.CANCELLED, h.engine.voice.value.state)
        h.say("취소해")
        assertTrue(h.last<EngineEvent.Clarification>().question.contains("초안"), "취소할 대상이 없으면 묻는다")
        val e = h.record("엔진오일 갈았어 8만 원")
        h.cmd(Command.CancelEntry(e.id, "minji"))
        assertEquals(0L, ledger(h).total())
        assertEquals(2, h.repo.entry(e.id)!!.version, "확정 기록 취소도 이력으로 남는다")
        h.cmd(Command.CancelEntry(e.id, "minji"))
        assertEquals(RejectReason.INVALID_STATE, h.last<EngineEvent.Rejected>().reason)
    }

    @Test fun sameSessionRewritesTheSameDraftAndDuplicatesNeedConsent() = runTest {
        val h = Harness(this)
        h.cmd(Command.VoiceStarted("s", Fx.CAR, "minji"))
        h.cmd(Command.VoiceFinal("s", "엔진오일 갈았어"))
        h.cmd(Command.VoiceFinal("s", "엔진오일 갈았어 9만 원"))
        assertEquals(1, h.repo.entries().size, "같은 음성 세션은 같은 초안을 고친다"); assertEquals(Money(90_000), h.repo.entries().single().cost)
        h.cmd(Command.ConfirmEntry(h.repo.entries().single().id, "minji"))
        h.cmd(Command.ConfirmEntry(h.repo.entries().single().id, "minji"))
        assertEquals(RejectReason.INVALID_STATE, h.last<EngineEvent.Rejected>().reason, "두 번 확정해도 한 건")

        h.cmd(Command.ManualDraft(Fx.CAR, "junho", EntryType.MAINTENANCE, MaintenanceItem.ENGINE_OIL, cost = 90_000))
        val dup = h.last<EngineEvent.DraftReady>().entry
        h.cmd(Command.ConfirmEntry(dup.id, "junho"))
        assertEquals(RejectReason.DUPLICATE, h.last<EngineEvent.Rejected>().reason)
        assertEquals(90_000L, ledger(h).total())
        h.cmd(Command.ConfirmEntry(dup.id, "junho", allowDuplicate = true))
        assertEquals(180_000L, ledger(h).total(), "사용자가 중복이 아니라고 하면 저장")
    }

    @Test fun drivingRestrictionBlocksInputButNotCleanup() = runTest {
        val h = Harness(this)
        h.driving = true
        h.say("엔진오일 갈았어")
        assertTrue(h.all<EngineEvent.Rejected>().all { it.reason == RejectReason.DRIVING_RESTRICTED })
        assertEquals(VoiceFailure.DRIVING_RESTRICTED, h.engine.voice.value.failure)
        assertTrue(h.repo.entries().isEmpty())
        h.cmd(Command.OpenToday(Fx.CAR, "minji"))
        assertEquals(RejectReason.DRIVING_RESTRICTED, h.last<EngineEvent.Rejected>().reason)
        h.cmd(Command.VoiceCancelled("s1"))
        assertEquals(VoiceState.CANCELLED, h.engine.voice.value.state, "세션 정리는 주행 중에도 받는다")
    }

    @Test fun unknownDrivingStateIsTreatedAsRestricted() = runTest {
        val h = Harness(this)
        h.gateKnown = false
        h.say("엔진오일 갈았어")
        assertTrue(h.all<EngineEvent.Rejected>().isNotEmpty() && h.all<EngineEvent.Rejected>().all { it.reason == RejectReason.DRIVING_RESTRICTED },
            "차량 서비스에 연결하지 못하면 안전하게 제한으로 본다")
        assertTrue(h.repo.entries().isEmpty())
    }

    @Test fun voiceEndedWithoutFinalResult() = runTest {
        val h = Harness(this)
        h.cmd(Command.VoiceStarted("s1", Fx.CAR, "minji"))
        h.cmd(Command.VoicePartial("s1", "엔진오일 갈았어 8만 원"))
        h.cmd(Command.VoiceEnded("s1"))
        val d = h.last<EngineEvent.DraftReady>().entry
        assertEquals(MaintenanceItem.ENGINE_OIL, d.item); assertEquals(Money(80_000), d.cost, "마지막 중간 결과로 초안을 만든다")
        assertEquals(VoiceState.DRAFT, h.engine.voice.value.state)
        h.cmd(Command.VoiceEnded("s1"))
        assertEquals(1, h.repo.entries().size, "이미 끝난 세션은 무시한다")
        h.cmd(Command.VoiceStarted("s2", Fx.CAR, "minji"))
        h.cmd(Command.VoiceEnded("s2"))
        assertEquals(VoiceFailure.NO_SPEECH, h.engine.voice.value.failure, "아무 말도 없었으면 저장 성공이 아니라 실패")
        assertEquals(1, h.repo.entries().size)
    }

    @Test fun draftQuestionsAndNoticesFollowTheUtteranceDictionary() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0))
        h.say("엔진오일 갈았어 8만 원")
        assertEquals(setOf(DraftQuestion.DATE_ASSUMED_TODAY), h.last<EngineEvent.DraftReady>().questions, "날짜를 안 말했으면 정비 직후인지 묻는다")
        h.say("타이어 위치 교환했어 4만 원")
        assertTrue(DraftQuestion.CONFIRM_ROTATION in h.last<EngineEvent.DraftReady>().questions, "새 타이어 교체와 구분")
        h.say("오늘 주유했어 5만 원")
        assertEquals(setOf(DraftNotice.NO_CANDIDATE, DraftNotice.AMOUNT_NOT_ESTIMATED), h.last<EngineEvent.DraftReady>().notices)
        h.say("워셔액 채웠어")
        assertEquals(setOf(DraftNotice.REFILL_NOT_WARNING_RESET), h.last<EngineEvent.DraftReady>().notices)
        h.say("브레이크 밟을 때 끼익 소리 나")
        assertEquals(setOf(DraftNotice.MEMO_NOT_DIAGNOSIS), h.last<EngineEvent.DraftReady>().notices)
        h.cmd(Command.ManualDraft(Fx.CAR, "minji", EntryType.MAINTENANCE, MaintenanceItem.WIPER))
        assertTrue(DraftQuestion.DATE_ASSUMED_TODAY !in h.last<EngineEvent.DraftReady>().questions, "화면에서 고른 날짜는 묻지 않는다")
        assertTrue(DraftQuestion.entries.all { it.prompt.isNotBlank() } && DraftNotice.entries.all { it.text.isNotBlank() })
    }

    @Test fun inconsistentOdometerIsFlaggedButSaved() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0))
        h.record("오늘 엔진오일 갈았어 8만 원")
        val oil = h.repo.entries().single()
        h.cmd(Command.ManualDraft(Fx.CAR, "junho", EntryType.MAINTENANCE, MaintenanceItem.TIRE_ROTATION, performedOn = LocalDate.of(2026, 10, 1)))
        val rot = h.last<EngineEvent.DraftReady>().entry.id
        h.cmd(Command.EditEntry(rot, "junho", EntryEdit(odometerKm = 48_000.0)))
        h.cmd(Command.ConfirmEntry(rot, "junho"))
        assertEquals(EngineEvent.OdometerInconsistent(rot, oil.id), h.last<EngineEvent.OdometerInconsistent>(), "더 이른 날 기록의 km가 더 크다")
        assertTrue(h.repo.entry(rot)!!.isConfirmed, "막지 않고 알린다")
        h.clear()
        h.cmd(Command.EditEntry(rot, "junho", EntryEdit(odometerKm = 46_000.0)))
        assertTrue(h.all<EngineEvent.OdometerInconsistent>().isEmpty(), "고치면 더 알리지 않는다")
    }

    @Test fun voiceFailuresAreNeverSaved() = runTest {
        val h = Harness(this)
        h.cmd(Command.VoiceStarted("s", Fx.CAR, "minji"))
        assertEquals(VoiceState.LISTENING, h.engine.voice.value.state)
        h.cmd(Command.VoicePartial("s", "엔진"))
        assertEquals(VoiceState.RECOGNIZING, h.engine.voice.value.state); assertEquals("엔진", h.engine.voice.value.partial)
        h.cmd(Command.VoiceFailed("s", VoiceFailure.PERMISSION_DENIED))
        assertEquals(VoiceState.FAILED, h.engine.voice.value.state); assertTrue(h.repo.entries().isEmpty())
        h.say("음 그러니까")
        assertEquals(VoiceFailure.UNRECOGNIZED, h.engine.voice.value.failure)
        assertTrue(h.last<EngineEvent.Clarification>().question.contains("엔진오일 갈았어"))
        assertTrue(h.repo.entries().isEmpty())
    }

    @Test fun evProfileHidesEngineItems() = runTest {
        val h = Harness(this, Powertrain.EV)
        h.say("엔진오일 갈았어")
        assertEquals(RejectReason.NOT_AVAILABLE_FOR_POWERTRAIN, h.last<EngineEvent.Rejected>().reason)
        assertTrue(h.last<EngineEvent.Clarification>().question.contains("전기차"))
        h.say("주유했어 5만 원")
        assertEquals(VoiceFailure.NOT_APPLICABLE, h.engine.voice.value.failure)
        h.cmd(Command.SetPlan(Fx.CAR, MaintenanceItem.ENGINE_OIL, 10_000.0, 12, "매뉴얼"))
        assertEquals(RejectReason.NOT_AVAILABLE_FOR_POWERTRAIN, h.last<EngineEvent.Rejected>().reason)
        h.say("충전했어 1만 2천 원")
        assertEquals(EntryType.CHARGE, h.last<EngineEvent.DraftReady>().entry.type)
        assertTrue(h.repo.entries().none { it.item == MaintenanceItem.ENGINE_OIL })
    }

    @Test fun staleOrMissingOdometerIsNotAttached() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0.minus(Duration.ofMinutes(30))))
        h.obs(Fx.odo(null, Fx.T0))
        h.say("엔진오일 갈았어")
        val d = h.last<EngineEvent.DraftReady>()
        assertNull(d.entry.odometer, "오래된 값은 새 일지에 붙이지 않는다"); assertTrue(DraftQuestion.ODOMETER_UNAVAILABLE in d.questions)
        h.cmd(Command.OpenToday(Fx.CAR, "minji"))
        val o = h.last<EngineEvent.TodayReady>().briefing.odometer
        assertEquals(OdometerState.STALE, o.state); assertEquals(47_200.0, o.km, "미수신(null)은 마지막 값을 0으로 덮지 않는다")
    }

    @Test fun odometerRegressionWaitsForReview() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0.minusSeconds(60)))
        h.obs(Fx.odo(1_200.0, Fx.T0))
        assertEquals(EngineEvent.OdometerReviewNeeded(Fx.CAR, 1_200.0, 47_200.0), h.last<EngineEvent.OdometerReviewNeeded>())
        h.say("엔진오일 갈았어")
        assertNull(h.last<EngineEvent.DraftReady>().entry.odometer, "검토 대기 중에는 붙이지 않는다")
        h.cmd(Command.ResolveOdometerReview(Fx.CAR, accept = true))
        h.cmd(Command.OpenToday(Fx.CAR, "minji"))
        assertEquals(1_200.0, h.last<EngineEvent.TodayReady>().briefing.odometer.km)
    }

    @Test fun userOverrideKeepsTheVehicleValue() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0))
        h.say("엔진오일 갈았어")
        val id = h.last<EngineEvent.DraftReady>().entry.id
        h.cmd(Command.EditEntry(id, "minji", EntryEdit(odometerKm = 47_150.0)))
        val e = h.repo.entry(id)!!
        assertEquals(EvidenceSource.USER, e.odometer!!.source); assertEquals(47_150.0, e.odometer!!.value)
        assertEquals(EvidenceSource.VEHICLE, e.supersededOdometer!!.source); assertEquals(47_200.0, e.supersededOdometer!!.value)
        h.cmd(Command.EditEntry(id, "minji", EntryEdit(cost = -1)))
        assertEquals(RejectReason.INVALID_VALUE, h.last<EngineEvent.Rejected>().reason)
        h.cmd(Command.EditEntry(id, "minji", EntryEdit(performedOn = Fx.TODAY.plusDays(1))))
        assertEquals(RejectReason.INVALID_VALUE, h.last<EngineEvent.Rejected>().reason, "미래 날짜는 받지 않는다")
    }

    @Test fun scheduleQueryByVoice() = runTest {
        val h = Harness(this)
        h.obs(Fx.odo(47_200.0, Fx.T0))
        h.record("엔진오일 갈았어 8만 원")
        h.cmd(Command.SetPlan(Fx.CAR, MaintenanceItem.ENGINE_OIL, 10_000.0, 12, "매뉴얼"))
        h.say("다음 관리는 언제야?")
        assertEquals(57_200.0, h.last<EngineEvent.ScheduleAnswer>().statuses.single().nextKm)
        assertEquals(VoiceState.IDLE, h.engine.voice.value.state)
        h.cmd(Command.SetPlan(Fx.CAR, MaintenanceItem.WASHER_FLUID, 1.0, null, "x"))
        assertEquals(RejectReason.INVALID_VALUE, h.last<EngineEvent.Rejected>().reason, "보충 항목은 주기 관리 대상이 아니다")
        h.cmd(Command.SetPlan(Fx.CAR, MaintenanceItem.TIRE_ROTATION, null, null, "x"))
        assertEquals(RejectReason.INVALID_VALUE, h.last<EngineEvent.Rejected>().reason)
    }
}
