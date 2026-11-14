package ai.chagok.core

import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.learning.Topic
import ai.chagok.core.model.Powertrain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 관리 도감: 공모전 시연의 두 도장('첫 일지', '교환 주기 읽기')과 보상 규칙. */
class LearningTest {
    private fun Harness.setupOil() {
        obs(Fx.odo(47_200.0, Fx.T0))
        record("오늘 엔진오일 갈았어. 8만 원")
        cmd(Command.SetPlan(Fx.CAR, MaintenanceItem.ENGINE_OIL, 10_000.0, 12, "사용자 확인: 차량 매뉴얼"))
    }

    private fun Harness.solve(member: String, topic: Topic) {
        cmd(Command.StartLesson(member, Fx.CAR, topic))
        for (q in last<EngineEvent.LessonReady>().lesson.questions) cmd(Command.AnswerQuiz(member, topic, q.id, q.answer))
    }

    @Test fun firstDiaryStampWithHintOnWrongAnswer() = runTest {
        val h = Harness(this); h.setupOil()
        h.cmd(Command.StartLesson("minji", Fx.CAR, Topic.FIRST_DIARY))
        val lesson = h.last<EngineEvent.LessonReady>().lesson
        assertEquals(listOf("km", "date", "item"), lesson.questions.map { it.id })
        assertTrue(lesson.questions[0].prompt.contains("47,200km"), "내 일지의 값으로 묻는다")
        h.cmd(Command.AnswerQuiz("minji", Topic.FIRST_DIARY, "km", 1))
        val wrong = h.last<EngineEvent.QuizFeedback>()
        assertTrue(!wrong.correct && wrong.hint != null && !wrong.lessonComplete, "오답은 힌트를 주고 다시 풀게 한다")
        assertTrue(h.all<EngineEvent.StampAwarded>().isEmpty())
        for (q in lesson.questions) h.cmd(Command.AnswerQuiz("minji", Topic.FIRST_DIARY, q.id, q.answer))
        assertTrue(h.last<EngineEvent.QuizFeedback>().lessonComplete)
        val stamp = h.last<EngineEvent.StampAwarded>().progress
        assertEquals(Topic.FIRST_DIARY, stamp.topic); assertEquals(lesson.basisEntryId, stamp.basisEntryId)
        h.solve("minji", Topic.FIRST_DIARY)
        assertEquals(1, h.all<EngineEvent.StampAwarded>().size, "같은 주제는 도장 한 번")
    }

    @Test fun readIntervalUsesMyOwnSchedule() = runTest {
        val h = Harness(this); h.setupOil()
        h.cmd(Command.StartLesson("minji", Fx.CAR, Topic.READ_INTERVAL))
        val lesson = h.last<EngineEvent.LessonReady>().lesson
        assertEquals("57,200km 또는 2026.11.08 중 먼저 오는 때".replace("2026", "2027"), lesson.questions[0].choices[0])
        assertTrue(lesson.explanation.contains("10,000km · 12개월")); assertTrue(lesson.explanation.contains("모든 차의 권장 주기는 아니에요"))
        h.solve("minji", Topic.READ_INTERVAL)
        assertEquals(Topic.READ_INTERVAL, h.last<EngineEvent.StampAwarded>().progress.topic)
    }

    @Test fun progressIsPerMemberAndOneMissionIsRecommended() = runTest {
        val h = Harness(this); h.setupOil()
        h.cmd(Command.OpenToday(Fx.CAR, "minji"))
        assertEquals(Topic.FIRST_DIARY, h.last<EngineEvent.TodayReady>().briefing.recommendedTopic)
        h.solve("minji", Topic.FIRST_DIARY)
        h.cmd(Command.OpenToday(Fx.CAR, "minji"))
        assertEquals(Topic.READ_INTERVAL, h.last<EngineEvent.TodayReady>().briefing.recommendedTopic)
        h.solve("minji", Topic.READ_INTERVAL)
        h.cmd(Command.OpenToday(Fx.CAR, "minji"))
        assertNull(h.last<EngineEvent.TodayReady>().briefing.recommendedTopic, "다 배우면 더 권하지 않는다")
        h.cmd(Command.OpenToday(Fx.CAR, "junho"))
        assertEquals(Topic.FIRST_DIARY, h.last<EngineEvent.TodayReady>().briefing.recommendedTopic, "가족 기록을 읽은 사람도 별도 진행")
        assertTrue(h.repo.progress("junho").isEmpty())
    }

    @Test fun lessonsNeedABasisAndAreMvpOnly() = runTest {
        val h = Harness(this)
        h.cmd(Command.StartLesson("minji", Fx.CAR, Topic.FIRST_DIARY))
        assertEquals(RejectReason.LESSON_NOT_AVAILABLE, h.last<EngineEvent.Rejected>().reason, "일지가 없으면 열리지 않는다")
        h.setupOil()
        h.cmd(Command.StartLesson("minji", Fx.CAR, Topic.TIRE_BASICS))
        assertEquals(RejectReason.LESSON_NOT_AVAILABLE, h.last<EngineEvent.Rejected>().reason, "확장 주제는 MVP에서 닫혀 있다")
        h.cmd(Command.AnswerQuiz("minji", Topic.FIRST_DIARY, "km", 0))
        assertEquals(RejectReason.NOT_FOUND, h.last<EngineEvent.Rejected>().reason, "시작하지 않은 학습")
        h.driving = true
        h.cmd(Command.StartLesson("minji", Fx.CAR, Topic.FIRST_DIARY))
        assertEquals(RejectReason.DRIVING_RESTRICTED, h.last<EngineEvent.Rejected>().reason, "학습은 정차 중에만")
    }

    @Test fun cancellingTheBasisRebindsOrUnlinksButKeepsTheStamp() = runTest {
        val h = Harness(this); h.setupOil()
        val rotation = h.record("타이어 위치 교환했어")
        h.solve("minji", Topic.FIRST_DIARY)
        val oilId = h.last<EngineEvent.StampAwarded>().progress.basisEntryId!!
        h.cmd(Command.CancelEntry(oilId, "minji"))
        assertEquals(rotation.id, h.last<EngineEvent.LearningBasisChanged>().progress.basisEntryId, "다른 유효 기록으로 근거를 옮긴다")
        h.cmd(Command.CancelEntry(rotation.id, "minji"))
        assertNull(h.last<EngineEvent.LearningBasisChanged>().progress.basisEntryId, "근거가 없으면 연결만 해제한다")
        assertNotNull(h.repo.progress("minji", Topic.FIRST_DIARY), "학습 완료는 정비 기록과 별개라 유지한다")
    }

    @Test fun evUsesTireRotationForIntervals() = runTest {
        val h = Harness(this, Powertrain.EV)
        h.obs(Fx.odo(12_000.0, Fx.T0))
        h.record("타이어 위치 교환했어")
        h.cmd(Command.SetPlan(Fx.CAR, MaintenanceItem.TIRE_ROTATION, 10_000.0, null, "사용자 확인"))
        h.cmd(Command.StartLesson("minji", Fx.CAR, Topic.READ_INTERVAL))
        val l = h.last<EngineEvent.LessonReady>().lesson
        assertTrue(l.explanation.startsWith("타이어 위치 교환")); assertEquals(listOf("only"), l.questions.map { it.id })
        assertEquals("22,000km", l.questions.single().choices[0])
    }
}
