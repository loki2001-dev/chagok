package ai.chagok.core

import ai.chagok.core.detect.CandidateStatus
import ai.chagok.core.detect.EnergyCandidate
import ai.chagok.core.detect.EnergyDetector
import ai.chagok.core.detect.Rise
import ai.chagok.core.diary.DiaryEntry
import ai.chagok.core.diary.EntryStatus
import ai.chagok.core.diary.EntryType
import ai.chagok.core.diary.MaintenanceItem
import ai.chagok.core.diary.Money
import ai.chagok.core.diary.PlanKind
import ai.chagok.core.diary.Revision
import ai.chagok.core.diary.Wheel
import ai.chagok.core.learning.LearningCatalog
import ai.chagok.core.learning.LearningProgress
import ai.chagok.core.learning.Lesson
import ai.chagok.core.learning.Topic
import ai.chagok.core.model.EvidenceSource
import ai.chagok.core.model.FieldEvidence
import ai.chagok.core.model.Observation
import ai.chagok.core.model.SourceStatus
import ai.chagok.core.model.VehicleProfile
import ai.chagok.core.model.VehicleSignal
import ai.chagok.core.pipeline.CommandBus
import ai.chagok.core.pipeline.ObservationBus
import ai.chagok.core.pipeline.VehicleDataProducer
import ai.chagok.core.schedule.DueState
import ai.chagok.core.schedule.MaintenancePlan
import ai.chagok.core.schedule.PlanNotify
import ai.chagok.core.schedule.ScheduleCalculator
import ai.chagok.core.schedule.ScheduleConfig
import ai.chagok.core.schedule.ScheduleStatus
import ai.chagok.core.share.CheckItem
import ai.chagok.core.share.OdometerState
import ai.chagok.core.share.OdometerView
import ai.chagok.core.share.ReportExporter
import ai.chagok.core.share.TodayBriefing
import ai.chagok.core.store.Repository
import ai.chagok.core.voice.DateGuess
import ai.chagok.core.voice.UtteranceParser
import ai.chagok.core.voice.VoiceFailure
import ai.chagok.core.voice.VoiceIntent
import ai.chagok.core.voice.VoiceState
import ai.chagok.core.voice.VoiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 주행 규제 게이트. 앱은 CarUxRestrictions(isRequiresDistractionOptimization)로 구현한다.
 * restricted=true면 정차 전용 기능을 막는다. known=false(차량 서비스 미연결)면 안전하게 제한으로 보고, 정차 여부도 모른다고 본다.
 */
fun interface DrivingGate {
    fun restricted(): Boolean
    fun known(): Boolean = true
}

/**
 * 차곡 v4.2 일지 엔진. 차량 값·사용자 명령·Producer 상태를 한 코루틴에서 순서대로 처리하므로 내부 상태에 락이 없다.
 *
 * 세 가지 출처를 구분한다:
 *  - 차량: 받은 값(누적 주행거리·연료 잔량·SoC)에 수신 시각을 붙인다. 없거나 오래됐으면 붙이지 않고 0으로 채우지 않는다.
 *  - 사용자: 정비 내용·비용·실제 날짜·과거 주행거리는 사용자가 확인한다. 음성 인식 완료는 저장이 아니다.
 *  - 앱 계산: 다음 일정은 설정 주기로 계산하고, 잔량 상승은 확인 전까지 후보로만 둔다.
 *
 * 출력: [events] 이산 사건(손실 없음, 단일 소비자) · [voice] 음성 입력 상태 · [sourceStatus] 신호별 수신 상태.
 * onObservation/onCommand는 테스트가 직접 부를 수 있게 공개했지만 start() 중에는 바깥에서 부르지 않는다.
 */
class Engine(
    private val repo: Repository,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    private val drivingGate: DrivingGate = DrivingGate { false },
    private val detector: EnergyDetector = EnergyDetector(),
    private val scheduleConfig: ScheduleConfig = ScheduleConfig(),
    /** 이보다 오래된 주행거리는 새 일지에 자동으로 붙이지 않는다("마지막 수신"으로만 표시). */
    private val odometerMaxAge: Duration = Duration.ofMinutes(10),
    private val odometerRegressionKm: Double = 0.5,
    /** 같은 주유·충전 중에 잔량이 단계적으로 오르면 하나의 후보로 합친다. */
    private val candidateMergeWindow: Duration = Duration.ofMinutes(30),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _events = Channel<EngineEvent>(Channel.UNLIMITED)
    val events: Flow<EngineEvent> = _events.receiveAsFlow()
    private val _voice = MutableStateFlow(VoiceStatus())
    val voice: StateFlow<VoiceStatus> = _voice.asStateFlow()
    private val _sourceStatus = MutableStateFlow(VehicleSignal.entries.associateWith { SourceStatus.NOT_STARTED })
    val sourceStatus: StateFlow<Map<VehicleSignal, SourceStatus>> = _sourceStatus.asStateFlow()

    private class Session(val vehicleId: String, val memberId: String, var entryId: String? = null)
    private class ActiveLesson(val lesson: Lesson, val solved: MutableSet<String> = HashSet())

    private val sessions = HashMap<String, Session>()
    private val lastVoiceEntry = HashMap<String, String>()          // vehicleId → 음성으로 만든 마지막 기록
    private val latestOdo = HashMap<String, Observation>()
    private val odoReview = HashMap<String, Observation>()
    private val lessons = HashMap<Pair<String, Topic>, ActiveLesson>()
    private val producerStatus = HashMap<String, Map<VehicleSignal, SourceStatus>>()
    private var job: Job? = null

    private sealed class In {
        data class Obs(val o: Observation) : In()
        data class Cmd(val c: Command) : In()
        data class Status(val producer: String, val s: Map<VehicleSignal, SourceStatus>) : In()
        data object Tick : In()
    }

    /**
     * tickEvery: 잔량 리스너는 값이 바뀔 때만 오므로, 이 간격으로 안정 판정을 진행한다(앱은 5초 정도를 준다).
     * 테스트처럼 시간을 직접 다룰 때는 null로 두고 [onTick]을 부른다.
     */
    fun start(
        scope: CoroutineScope, observations: ObservationBus, commands: CommandBus,
        producers: List<VehicleDataProducer> = emptyList(), tickEvery: Duration? = null,
    ): Job {
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            val inputs = ArrayList<Flow<In>>()
            inputs += observations.events.map { In.Obs(it) }
            inputs += commands.commands.map { In.Cmd(it) }
            producers.forEach { p -> inputs += p.status.map { In.Status(p.name, it) } }
            tickEvery?.let { d -> inputs += flow<In> { while (true) { delay(d.toMillis()); emit(In.Tick) } } }
            inputs.merge().collect {
                when (it) {
                    is In.Obs -> onObservation(it.o)
                    is In.Cmd -> onCommand(it.c)
                    is In.Status -> onProducerStatus(it.producer, it.s)
                    In.Tick -> onTick()
                }
            }
        }.also { job = it }
    }

    /** 정차 여부 힌트. 플랫폼이 주행 상태를 모르면 null(주행거리로 판단). */
    private fun parkedHint(): Boolean? = if (drivingGate.known()) !drivingGate.restricted() else null

    private fun emit(e: EngineEvent) { _events.trySend(e) }
    private fun now(): Instant = clock.instant()
    private fun today(): LocalDate = LocalDate.ofInstant(now(), zone)

    // ───────────────────────── 차량 값 ─────────────────────────

    fun onObservation(o: Observation) {
        if (o.value != null) _sourceStatus.value = _sourceStatus.value + (o.signal to SourceStatus.AVAILABLE)
        if (o.signal == VehicleSignal.ODOMETER_KM) { onOdometer(o); return }
        if (!detector.hasTrack(o.vehicleId, o.signal)) repo.anchor(o.vehicleId, o.signal)?.let { detector.restore(it) }
        val rise = detector.onObservation(o, latestOdometer(o.vehicleId)?.value, parkedHint())
        detector.anchor(o.vehicleId, o.signal)?.let { repo.saveAnchor(it) }
        rise?.let { onRise(o.vehicleId, it) }
    }

    /** 시간 경과. 값이 바뀌지 않아 리스너가 조용해도 잔량 안정 판정을 진행한다. */
    fun onTick() {
        val rises = detector.onTick(now(), { latestOdometer(it)?.value }, parkedHint())
        detector.anchors().forEach { repo.saveAnchor(it) }
        rises.forEach { onRise(it.before.vehicleId, it) }
    }

    fun onProducerStatus(producer: String, s: Map<VehicleSignal, SourceStatus>) {
        producerStatus[producer] = s
        _sourceStatus.value = VehicleSignal.entries.associateWith { sig ->
            val all = producerStatus.values.mapNotNull { it[sig] }
            when {
                all.isEmpty() -> SourceStatus.NOT_STARTED
                SourceStatus.AVAILABLE in all -> SourceStatus.AVAILABLE
                else -> all.maxBy { it.ordinal }
            }
        }
        // 수신이 끊긴 동안의 잔량 변화는 연속 관측이 아니다
        _sourceStatus.value.filterValues { it != SourceStatus.AVAILABLE }.keys.forEach { detector.markGap(it) }
    }

    private fun latestOdometer(vehicleId: String): Observation? =
        latestOdo[vehicleId] ?: repo.lastOdometer(vehicleId)?.also { latestOdo[vehicleId] = it }

    /** 누적 주행거리. 미수신은 무시(0으로 채우지 않음). 줄어든 값은 역행·차량 변경 의심이라 반영하지 않고 검토 대기로 둔다. */
    private fun onOdometer(o: Observation) {
        val v = o.value ?: return
        val prev = latestOdometer(o.vehicleId)?.value
        if (prev != null && v < prev - odometerRegressionKm) {
            if (odoReview[o.vehicleId]?.value != v) {
                odoReview[o.vehicleId] = o
                emit(EngineEvent.OdometerReviewNeeded(o.vehicleId, v, prev))
            }
            return
        }
        odoReview.remove(o.vehicleId)
        latestOdo[o.vehicleId] = o
        repo.saveLastOdometer(o)
    }

    /** 새 일지에 자동으로 붙여도 되는 주행거리: 받았고, 최근이고, 검토 대기가 아니어야 한다. */
    private fun attachableOdometer(vehicleId: String): Observation? =
        latestOdometer(vehicleId)?.takeIf { odoReview[vehicleId] == null && Duration.between(it.receivedAt, now()) <= odometerMaxAge }

    private fun onRise(vehicleId: String, r: Rise) {
        val v = repo.vehicle(vehicleId) ?: return
        val type = if (r.before.signal == VehicleSignal.FUEL_PERCENT) EntryType.FUEL else EntryType.CHARGE
        if (type == EntryType.FUEL && !v.powertrain.hasFuelTank) return
        if (type == EntryType.CHARGE && !v.powertrain.hasPlug) return   // HEV의 SoC 상승은 외부 충전이 아니다
        val pending = repo.candidates().filter { it.status == CandidateStatus.PENDING && it.vehicleId == vehicleId && it.type == type }.maxByOrNull { it.afterAt }
        if (pending != null && Duration.between(pending.afterAt, r.before.at) <= candidateMergeWindow && r.before.level >= pending.afterPercent - 1.0) {
            val up = pending.copy(afterPercent = r.afterLevel, afterAt = r.afterAt, continuous = pending.continuous && r.continuous)
            repo.saveCandidate(up); emit(EngineEvent.CandidateUpdated(up)); return
        }
        if (repo.candidates().any { it.vehicleId == vehicleId && it.type == type && it.beforeAt == r.before.at && it.afterAt == r.afterAt }) return
        val c = EnergyCandidate(newId(), vehicleId, type, r.before.level, r.before.at, r.afterLevel, r.afterAt, r.continuous, createdAt = now())
        repo.saveCandidate(c); emit(EngineEvent.CandidateCreated(c))
    }

    // ───────────────────────── 명령 ─────────────────────────

    fun onCommand(c: Command) {
        if (c.parkedOnly && (drivingGate.restricted() || !drivingGate.known())) {
            emit(EngineEvent.Rejected(c, RejectReason.DRIVING_RESTRICTED))
            if (c is Command.VoiceStarted || c is Command.VoiceFinal || c is Command.VoiceEnded) setVoice(sessionOf(c), VoiceState.FAILED, failure = VoiceFailure.DRIVING_RESTRICTED)
            return
        }
        when (c) {
            is Command.VoiceStarted -> { sessions[c.sessionId] = Session(c.vehicleId, c.memberId); setVoice(c.sessionId, VoiceState.LISTENING) }
            is Command.VoicePartial -> if (sessions.containsKey(c.sessionId)) setVoice(c.sessionId, VoiceState.RECOGNIZING, partial = c.text)
            is Command.VoiceFinal -> voiceFinal(c)
            is Command.VoiceEnded -> voiceEnded(c)
            is Command.VoiceFailed -> setVoice(c.sessionId, VoiceState.FAILED, failure = c.failure)
            is Command.VoiceCancelled -> setVoice(c.sessionId, VoiceState.CANCELLED)
            is Command.ManualDraft -> manualDraft(c)
            is Command.EditEntry -> edit(c, c.entryId, c.memberId, c.edit)
            is Command.ConfirmEntry -> confirm(c)
            is Command.CancelEntry -> cancel(c, c.entryId, c.memberId)
            is Command.SetPlan -> setPlan(c)
            is Command.SnoozePlan -> updatePlan(c, c.vehicleId, c.item) { it.copy(notify = PlanNotify.SNOOZED, snoozedUntil = c.until) }
            is Command.SetPlanNotify -> updatePlan(c, c.vehicleId, c.item) { it.copy(notify = c.notify, snoozedUntil = null) }
            is Command.QuerySchedule -> emit(EngineEvent.ScheduleAnswer(c.vehicleId, statuses(c.vehicleId)))
            is Command.ConfirmCandidate -> confirmCandidate(c)
            is Command.DismissCandidate -> dismissCandidate(c)
            is Command.OpenToday -> openToday(c)
            is Command.MarkFeedSeen -> repo.saveFeedSeen(c.memberId, now())
            is Command.ResolveOdometerReview -> resolveOdometerReview(c)
            is Command.Export -> export(c)
            is Command.StartLesson -> startLesson(c)
            is Command.AnswerQuiz -> answerQuiz(c)
        }
    }

    private fun sessionOf(c: Command): String? = when (c) {
        is Command.VoiceStarted -> c.sessionId; is Command.VoiceFinal -> c.sessionId; is Command.VoiceEnded -> c.sessionId; else -> null
    }

    private fun setVoice(sessionId: String?, state: VoiceState, partial: String? = null, failure: VoiceFailure? = null, entryId: String? = null) {
        _voice.value = VoiceStatus(sessionId, state, partial, failure, entryId ?: sessionId?.let { sessions[it]?.entryId })
    }

    // ── 음성 ──

    private fun voiceFinal(c: Command.VoiceFinal) {
        val s = sessions[c.sessionId] ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        val v = repo.vehicle(s.vehicleId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        when (val intent = UtteranceParser.parse(c.text, today())) {
            is VoiceIntent.Draft -> {
                notApplicable(v, intent.type, intent.item)?.let { msg ->
                    setVoice(c.sessionId, VoiceState.FAILED, failure = VoiceFailure.NOT_APPLICABLE)
                    emit(EngineEvent.Clarification(c.sessionId, msg))
                    emit(EngineEvent.Rejected(c, RejectReason.NOT_AVAILABLE_FOR_POWERTRAIN))
                    return
                }
                // 같은 세션에서 다시 말하면 새 기록을 만들지 않고 그 초안을 바꾼다(중복 생성 방지)
                val reuse = s.entryId?.let { repo.entry(it) }?.takeIf { it.status == EntryStatus.DRAFT }
                val e = newDraft(
                    v, s.memberId, intent.type, intent.item, intent.wheels, intent.date, intent.cost,
                    note = intent.memo, utterance = c.text, sourceKey = "voice:${c.sessionId}", reuseId = reuse?.id,
                )
                s.entryId = e.id; lastVoiceEntry[v.id] = e.id
                setVoice(c.sessionId, VoiceState.DRAFT, entryId = e.id)
            }
            is VoiceIntent.CorrectCost -> {
                val target = correctionTarget(v.id, intent.oldAmount)
                if (target == null) {
                    setVoice(c.sessionId, VoiceState.IDLE)
                    emit(EngineEvent.Clarification(c.sessionId, "어느 기록의 금액을 고칠까요?"))
                    return
                }
                edit(c, target.id, s.memberId, EntryEdit(cost = intent.newAmount))
                val after = repo.entry(target.id)!!
                setVoice(c.sessionId, if (after.isConfirmed) VoiceState.SAVED else VoiceState.DRAFT, entryId = after.id)
            }
            VoiceIntent.CancelDraft -> {
                val target = activeDraft(v.id)
                if (target == null) {
                    setVoice(c.sessionId, VoiceState.IDLE)
                    emit(EngineEvent.Clarification(c.sessionId, "취소할 초안이 없어요."))
                    return
                }
                cancel(c, target.id, s.memberId)
                setVoice(c.sessionId, VoiceState.CANCELLED, entryId = target.id)
            }
            VoiceIntent.QuerySchedule -> {
                emit(EngineEvent.ScheduleAnswer(v.id, statuses(v.id)))
                setVoice(c.sessionId, VoiceState.IDLE)
            }
            is VoiceIntent.Unrecognized -> {
                setVoice(c.sessionId, VoiceState.FAILED, failure = VoiceFailure.UNRECOGNIZED)
                emit(EngineEvent.Clarification(c.sessionId, "무엇을 했는지 다시 말씀해 주세요. 예: 오늘 엔진오일 갈았어, 8만 원"))
            }
        }
    }

    /**
     * 음성 인식이 끝났는데 최종 결과(completed=true)가 오지 않았다. 말소리가 없으면 엔진이 종료 이벤트만 보내거나
     * 아무것도 보내지 않는 경우가 있어(에뮬레이터 관찰), 앱은 stop() 뒤 잠시 기다렸다가 이 명령을 보낸다.
     * 중간 결과가 있으면 그것을 최종 텍스트로 쓰고, 없으면 "말소리 없음"으로 실패 처리한다. 이미 끝난 세션이면 무시한다.
     */
    private fun voiceEnded(c: Command.VoiceEnded) {
        val v = _voice.value
        if (v.sessionId != c.sessionId || !sessions.containsKey(c.sessionId)) return
        when (v.state) {
            VoiceState.RECOGNIZING ->
                if (!v.partial.isNullOrBlank()) voiceFinal(Command.VoiceFinal(c.sessionId, v.partial))
                else setVoice(c.sessionId, VoiceState.FAILED, failure = VoiceFailure.NO_SPEECH)
            VoiceState.LISTENING -> setVoice(c.sessionId, VoiceState.FAILED, failure = VoiceFailure.NO_SPEECH)
            else -> Unit
        }
    }

    private fun notApplicable(v: VehicleProfile, type: EntryType, item: MaintenanceItem?): String? = when {
        item != null && !item.availableFor(v.powertrain) -> "${v.powertrain.label} 프로필에는 ${item.label} 항목이 없어요."
        type == EntryType.FUEL && !v.powertrain.hasFuelTank -> "${v.powertrain.label} 프로필에는 주유 기록이 없어요."
        type == EntryType.CHARGE && !v.powertrain.hasPlug -> "${v.powertrain.label} 프로필에는 외부 충전 기록이 없어요."
        else -> null
    }

    /** 금액을 고칠 대상. 음성으로 만든 마지막 기록이 기본이고, "8만 원이 아니라"처럼 이전 금액을 말했는데 맞지 않으면 그 금액의 초안을 찾는다. */
    private fun correctionTarget(vehicleId: String, oldAmount: Long?): DiaryEntry? {
        val last = lastVoiceEntry[vehicleId]?.let { repo.entry(it) }?.takeIf { it.status != EntryStatus.CANCELLED }
        if (last != null && (oldAmount == null || last.cost?.amount == oldAmount || last.cost == null)) return last
        val drafts = drafts(vehicleId)
        if (oldAmount != null) drafts.filter { it.cost?.amount == oldAmount }.singleOrNull()?.let { return it }
        return drafts.singleOrNull()
    }

    private fun activeDraft(vehicleId: String): DiaryEntry? =
        lastVoiceEntry[vehicleId]?.let { repo.entry(it) }?.takeIf { it.status == EntryStatus.DRAFT } ?: drafts(vehicleId).singleOrNull()

    private fun drafts(vehicleId: String) = repo.entries().filter { it.vehicleId == vehicleId && it.status == EntryStatus.DRAFT }

    // ── 일지 ──

    private fun manualDraft(c: Command.ManualDraft) {
        val v = repo.vehicle(c.vehicleId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        notApplicable(v, c.type, c.item)?.let { return emit(EngineEvent.Rejected(c, RejectReason.NOT_AVAILABLE_FOR_POWERTRAIN)) }
        if (c.cost != null && c.cost < 0) return emit(EngineEvent.Rejected(c, RejectReason.INVALID_VALUE))
        val date = c.performedOn ?: today()
        if (date.isAfter(today())) return emit(EngineEvent.Rejected(c, RejectReason.INVALID_VALUE))
        newDraft(v, c.memberId, c.type, c.item, c.wheels, DateGuess(date, c.performedOn != null, false), c.cost, c.note, null, null, null)
    }

    /**
     * 초안 만들기. 오늘 한 관리면 지금 받은 주행거리를 수신 시각과 함께 붙이고,
     * 과거 관리면 현재 km를 참고값으로만 두고 당시 km를 묻는다(과거 정비에 오늘 값을 자동 확정하지 않는다).
     */
    private fun newDraft(
        v: VehicleProfile, memberId: String, type: EntryType, item: MaintenanceItem?, wheels: Set<Wheel>, date: DateGuess,
        cost: Long?, note: String?, utterance: String?, sourceKey: String?, reuseId: String?,
    ): DiaryEntry {
        val (odo, ref) = odometerFor(v.id, date.date)
        val candidate = if (type == EntryType.FUEL || type == EntryType.CHARGE)
            repo.candidates().filter { it.status == CandidateStatus.PENDING && it.vehicleId == v.id && it.type == type }.maxByOrNull { it.afterAt }
        else null
        val e = DiaryEntry(
            id = reuseId ?: newId(), vehicleId = v.id, type = type, item = item, wheels = wheels,
            performedOn = date.date, dateApproximate = date.approximate, dateAssumed = utterance != null && !date.stated, createdAt = now(), authorId = memberId,
            cost = cost?.let { Money(it) }, odometer = odo, referenceOdometer = ref, note = note, utterance = utterance,
            sourceKey = sourceKey, candidateId = candidate?.id,
        )
        repo.saveEntry(e)
        emit(EngineEvent.DraftReady(e, questions(e), candidate, notices(e)))
        return e
    }

    private fun odometerFor(vehicleId: String, date: LocalDate): Pair<FieldEvidence<Double>?, FieldEvidence<Double>?> {
        val ev = attachableOdometer(vehicleId)?.let { FieldEvidence(it.value!!, "km", EvidenceSource.VEHICLE, it.receivedAt) }
        return if (date.isBefore(today())) null to ev else ev to null
    }

    private fun questions(e: DiaryEntry): Set<DraftQuestion> = buildSet {
        val needsKm = e.type != EntryType.SYMPTOM_MEMO
        if (e.dateApproximate) add(DraftQuestion.CONFIRM_DATE)
        if (needsKm && e.odometer == null) add(if (e.performedOn.isBefore(today())) DraftQuestion.ASK_PAST_ODOMETER else DraftQuestion.ODOMETER_UNAVAILABLE)
        if (e.item == MaintenanceItem.TIRE_REPLACEMENT && e.wheels.isEmpty()) add(DraftQuestion.ASK_WHEELS)
        if (e.cost == null && e.type in setOf(EntryType.MAINTENANCE, EntryType.FUEL, EntryType.CHARGE)) add(DraftQuestion.ASK_COST)
        if (e.dateAssumed) add(DraftQuestion.DATE_ASSUMED_TODAY)
        if (e.item == MaintenanceItem.TIRE_ROTATION && e.type == EntryType.MAINTENANCE) add(DraftQuestion.CONFIRM_ROTATION)
    }

    /** 확인 화면에 함께 보여 줄 안내(기획서 07 발화 사전의 "확인할 내용"). */
    private fun notices(e: DiaryEntry): Set<DraftNotice> = buildSet {
        if (e.type == EntryType.FUEL || e.type == EntryType.CHARGE) {
            add(if (e.candidateId != null) DraftNotice.LINKED_TO_CANDIDATE else DraftNotice.NO_CANDIDATE)
            add(DraftNotice.AMOUNT_NOT_ESTIMATED)
        }
        if (e.type == EntryType.REFILL) add(DraftNotice.REFILL_NOT_WARNING_RESET)
        if (e.type == EntryType.SYMPTOM_MEMO) add(DraftNotice.MEMO_NOT_DIAGNOSIS)
    }

    /**
     * 주행거리 앞뒤가 맞지 않는 확정 기록이 있으면 알린다(더 이른 날 기록의 km가 더 크거나, 더 늦은 날 기록의 km가 더 작다).
     * 저장을 막지는 않는다: 과거 km를 잘못 입력했을 수도, 계기판 교체였을 수도 있어 사용자가 판단한다.
     */
    private fun checkOdometerConsistency(e: DiaryEntry) {
        val km = e.odometer?.value ?: return
        val conflict = repo.entries().firstOrNull { o ->
            val ok = o.odometer?.value
            o.id != e.id && o.isConfirmed && o.vehicleId == e.vehicleId && ok != null &&
                ((o.performedOn.isBefore(e.performedOn) && ok > km + odometerRegressionKm) ||
                    (o.performedOn.isAfter(e.performedOn) && ok < km - odometerRegressionKm))
        } ?: return
        emit(EngineEvent.OdometerInconsistent(e.id, conflict.id))
    }

    private fun edit(c: Command, entryId: String, memberId: String, ed: EntryEdit) {
        val e = repo.entry(entryId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        if (e.status == EntryStatus.CANCELLED) return emit(EngineEvent.Rejected(c, RejectReason.INVALID_STATE))
        val v = repo.vehicle(e.vehicleId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        if ((ed.cost != null && ed.cost < 0) || (ed.odometerKm != null && ed.odometerKm < 0) || (ed.performedOn != null && ed.performedOn.isAfter(today())))
            return emit(EngineEvent.Rejected(c, RejectReason.INVALID_VALUE))
        if (ed.item != null && !ed.item.availableFor(v.powertrain)) return emit(EngineEvent.Rejected(c, RejectReason.NOT_AVAILABLE_FOR_POWERTRAIN))

        val revs = ArrayList<Revision>()
        val nextVersion = e.version + 1
        fun rev(field: String, before: Any?, after: Any?) {
            if (e.isConfirmed && before != after) revs += Revision(nextVersion, now(), memberId, field, before?.toString(), after?.toString())
        }
        var n = e
        if (ed.clearCost || ed.cost != null) {
            val cost = if (ed.clearCost) null else Money(ed.cost!!, e.cost?.currency ?: "KRW")
            rev("cost", e.cost?.amount, cost?.amount); n = n.copy(cost = cost)
        }
        if (ed.performedOn != null) {
            rev("performedOn", e.performedOn, ed.performedOn)
            n = n.copy(performedOn = ed.performedOn, dateApproximate = false, dateAssumed = false)
            if (n.odometer?.source != EvidenceSource.USER) {
                val (odo, ref) = odometerFor(e.vehicleId, ed.performedOn)
                n = n.copy(odometer = if (e.isConfirmed) n.odometer?.takeIf { !ed.performedOn.isBefore(today()) } else odo, referenceOdometer = ref)
            }
        }
        if (ed.odometerKm != null) {
            rev("odometer", e.odometer?.value, ed.odometerKm)
            // 차량 값을 사용자가 고치면 사용자 제공 값으로 바뀌고, 원래 관측값은 따로 보존한다
            val superseded = if (n.odometer?.source == EvidenceSource.VEHICLE) n.odometer else n.supersededOdometer
            n = n.copy(odometer = FieldEvidence(ed.odometerKm, "km", EvidenceSource.USER), supersededOdometer = superseded)
        }
        if (ed.item != null) { rev("item", e.item, ed.item); n = n.copy(item = ed.item) }
        if (ed.wheels != null) { rev("wheels", e.wheels.joinToString(), ed.wheels.joinToString()); n = n.copy(wheels = ed.wheels) }
        if (ed.note != null) { rev("note", e.note, ed.note); n = n.copy(note = ed.note) }
        if (e.isConfirmed && revs.isNotEmpty()) n = n.copy(version = nextVersion, revisions = e.revisions + revs)
        repo.saveEntry(n)
        if (n.isConfirmed) {
            emit(EngineEvent.EntryUpdated(n)); emitSchedule(n.vehicleId)
            if (ed.odometerKm != null || ed.performedOn != null) checkOdometerConsistency(n)
        } else emit(EngineEvent.DraftReady(n, questions(n), n.candidateId?.let { repo.candidate(it) }, notices(n)))
    }

    private fun confirm(c: Command.ConfirmEntry) {
        val e = repo.entry(c.entryId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        if (e.status != EntryStatus.DRAFT) return emit(EngineEvent.Rejected(c, RejectReason.INVALID_STATE))
        if (e.dateApproximate && !c.acceptApproximateDate) return emit(EngineEvent.Rejected(c, RejectReason.DATE_NEEDS_CONFIRMATION))
        if (!c.allowDuplicate && duplicateOf(e) != null) return emit(EngineEvent.Rejected(c, RejectReason.DUPLICATE))
        val done = e.copy(status = EntryStatus.CONFIRMED, dateApproximate = false, confirmedAt = now(), confirmedBy = c.memberId)
        repo.saveEntry(done)
        done.candidateId?.let { id ->
            repo.candidate(id)?.takeIf { it.status == CandidateStatus.PENDING }?.let {
                val r = it.copy(status = CandidateStatus.CONFIRMED, linkedEntryId = done.id)
                repo.saveCandidate(r); emit(EngineEvent.CandidateResolved(r))
            }
        }
        emit(EngineEvent.EntryConfirmed(done))
        checkOdometerConsistency(done)
        sessions.entries.firstOrNull { it.value.entryId == done.id }?.let { setVoice(it.key, VoiceState.SAVED, entryId = done.id) }
        emitSchedule(done.vehicleId)
        repo.vehicle(done.vehicleId)?.let { v -> recommend(c.memberId, v)?.let { emit(EngineEvent.LearningSuggested(c.memberId, it)) } }
    }

    /** 같은 관리를 두 번 저장했는지. 정비·점검·보충은 같은 날 같은 항목, 주유·충전은 같은 후보이거나 같은 날 같은 금액. */
    private fun duplicateOf(e: DiaryEntry): DiaryEntry? = repo.entries().firstOrNull { o ->
        o.id != e.id && o.isConfirmed && o.vehicleId == e.vehicleId && o.type == e.type && o.performedOn == e.performedOn && when (e.type) {
            EntryType.MAINTENANCE, EntryType.INSPECTION, EntryType.REFILL -> o.item == e.item && o.wheels == e.wheels
            EntryType.FUEL, EntryType.CHARGE -> (e.candidateId != null && o.candidateId == e.candidateId) || (e.cost != null && o.cost == e.cost)
            EntryType.SYMPTOM_MEMO -> o.note == e.note
        }
    }

    private fun cancel(c: Command, entryId: String, memberId: String) {
        val e = repo.entry(entryId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        if (e.status == EntryStatus.CANCELLED) return emit(EngineEvent.Rejected(c, RejectReason.INVALID_STATE))
        val wasConfirmed = e.isConfirmed
        val done = if (wasConfirmed) e.copy(
            status = EntryStatus.CANCELLED, version = e.version + 1,
            revisions = e.revisions + Revision(e.version + 1, now(), memberId, "status", e.status.name, EntryStatus.CANCELLED.name),
        ) else e.copy(status = EntryStatus.CANCELLED)
        repo.saveEntry(done)
        // 이 기록으로 확정된 후보는 다시 확인 대기로 돌린다
        e.candidateId?.let { id ->
            repo.candidate(id)?.takeIf { it.linkedEntryId == e.id }?.let {
                val r = it.copy(status = CandidateStatus.PENDING, linkedEntryId = null); repo.saveCandidate(r); emit(EngineEvent.CandidateUpdated(r))
            }
        }
        emit(EngineEvent.EntryCancelled(done))
        sessions.entries.firstOrNull { it.value.entryId == done.id }?.let { if (!wasConfirmed) setVoice(it.key, VoiceState.CANCELLED, entryId = done.id) }
        if (wasConfirmed) { recheckLearningBasis(done); emitSchedule(done.vehicleId) }
    }

    // ── 관리 일정 ──

    private fun setPlan(c: Command.SetPlan) {
        val v = repo.vehicle(c.vehicleId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        if (!c.item.availableFor(v.powertrain)) return emit(EngineEvent.Rejected(c, RejectReason.NOT_AVAILABLE_FOR_POWERTRAIN))
        val invalid = c.item.planKind == PlanKind.NONE || (c.intervalKm == null && c.intervalMonths == null) ||
            (c.intervalKm != null && c.intervalKm <= 0.0) || (c.intervalMonths != null && c.intervalMonths <= 0) || c.source.isBlank()
        if (invalid) return emit(EngineEvent.Rejected(c, RejectReason.INVALID_VALUE))
        val prev = repo.plan(c.vehicleId, c.item)
        repo.savePlan(MaintenancePlan(c.vehicleId, c.item, c.intervalKm, c.intervalMonths, c.source, today(), prev?.notify ?: PlanNotify.ON, prev?.snoozedUntil))
        emitSchedule(c.vehicleId)
    }

    private fun updatePlan(c: Command, vehicleId: String, item: MaintenanceItem, f: (MaintenancePlan) -> MaintenancePlan) {
        val p = repo.plan(vehicleId, item) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        repo.savePlan(f(p)); emitSchedule(vehicleId)
    }

    /** 일정 계산용 현재 km: 마지막으로 받은 값(오래됐어도 현재보다 작거나 같은 하한이라 도래 판단에는 쓸 수 있다). 검토 대기 값은 쓰지 않는다. */
    fun statuses(vehicleId: String): List<ScheduleStatus> {
        val v = repo.vehicle(vehicleId) ?: return emptyList()
        val km = latestOdometer(vehicleId)?.value
        val entries = repo.entries()
        return repo.plans(vehicleId).filter { it.item.availableFor(v.powertrain) }
            .map { ScheduleCalculator.status(it, entries, km, today(), scheduleConfig) }
            .sortedWith(ScheduleCalculator.urgency)
    }

    private fun emitSchedule(vehicleId: String) = emit(EngineEvent.ScheduleChanged(vehicleId, statuses(vehicleId)))

    // ── 주유·충전 후보 ──

    private fun confirmCandidate(c: Command.ConfirmCandidate) {
        val cand = repo.candidate(c.candidateId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        if (cand.status != CandidateStatus.PENDING) return emit(EngineEvent.Rejected(c, RejectReason.INVALID_STATE))
        if (c.cost != null && c.cost < 0) return emit(EngineEvent.Rejected(c, RejectReason.INVALID_VALUE))
        // "주유했어. 5만 원"으로 이미 이 후보에 연결된 초안이 있으면 그 초안을 확정한다
        val linked = repo.entries().firstOrNull { it.candidateId == cand.id && it.status == EntryStatus.DRAFT }
        val draftId = if (linked != null) {
            if (c.cost != null) edit(c, linked.id, c.memberId, EntryEdit(cost = c.cost))
            linked.id
        } else {
            val v = repo.vehicle(cand.vehicleId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
            val day = LocalDate.ofInstant(cand.afterAt, zone)
            newDraft(v, c.memberId, cand.type, null, emptySet(), DateGuess(day, true, false), c.cost, null, null, "candidate:${cand.id}", null).id
        }
        confirm(Command.ConfirmEntry(draftId, c.memberId))
    }

    private fun dismissCandidate(c: Command.DismissCandidate) {
        val cand = repo.candidate(c.candidateId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        if (cand.status != CandidateStatus.PENDING) return emit(EngineEvent.Rejected(c, RejectReason.INVALID_STATE))
        val r = cand.copy(status = CandidateStatus.DISMISSED); repo.saveCandidate(r)
        repo.entries().filter { it.candidateId == cand.id && it.status == EntryStatus.DRAFT }.forEach { repo.saveEntry(it.copy(candidateId = null)) }
        emit(EngineEvent.CandidateResolved(r))
    }

    // ── 오늘의 관리 · 가족 ──

    private fun openToday(c: Command.OpenToday) {
        val v = repo.vehicle(c.vehicleId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        val last = latestOdometer(v.id)
        val review = odoReview[v.id]
        val odo = when {
            review != null -> OdometerView(last?.value, last?.receivedAt, OdometerState.REVIEW, review.value)
            last == null -> OdometerView(null, null, OdometerState.NONE)
            Duration.between(last.receivedAt, now()) <= odometerMaxAge -> OdometerView(last.value, last.receivedAt, OdometerState.RECEIVED)
            else -> OdometerView(last.value, last.receivedAt, OdometerState.STALE)
        }
        val schedule = statuses(v.id)
        val next = schedule.firstOrNull { !it.plan.silencedOn(today()) && it.state in setOf(DueState.DUE, DueState.REVIEW, DueState.SOON, DueState.UPCOMING) }
        val toCheck = buildList {
            review?.value?.let { add(CheckItem.OdometerReview(it, last?.value)) }
            drafts(v.id).sortedBy { it.createdAt }.forEach { add(CheckItem.PendingDraft(it)) }
            repo.candidates().filter { it.vehicleId == v.id && it.status == CandidateStatus.PENDING }.sortedBy { it.afterAt }.forEach { add(CheckItem.PendingCandidate(it)) }
        }
        val seen = repo.feedSeen(c.memberId)
        val family = repo.entries().filter {
            it.vehicleId == v.id && it.isConfirmed && it.confirmedBy != c.memberId && it.authorId != c.memberId &&
                (seen == null || (it.confirmedAt != null && it.confirmedAt.isAfter(seen)))
        }.sortedByDescending { it.confirmedAt }.take(10)
        emit(EngineEvent.TodayReady(TodayBriefing(v.id, c.memberId, odo, next, schedule, toCheck, family, recommend(c.memberId, v))))
    }

    private fun resolveOdometerReview(c: Command.ResolveOdometerReview) {
        val pending = odoReview.remove(c.vehicleId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        if (c.accept) { latestOdo[c.vehicleId] = pending; repo.saveLastOdometer(pending) }
        emitSchedule(c.vehicleId)
    }

    private fun export(c: Command.Export) {
        val v = repo.vehicle(c.spec.vehicleId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        if (c.spec.to.isBefore(c.spec.from)) return emit(EngineEvent.Rejected(c, RejectReason.INVALID_VALUE))
        emit(EngineEvent.ReportReady(ReportExporter.build(c.spec, v, repo.entries(), repo.members())))
    }

    // ── 관리 도감 ──

    /** 상황에 맞는 학습 하나만 추천한다. 이 구성원이 아직 도장을 받지 않았고 근거 일지가 있는 MVP 주제. */
    private fun recommend(memberId: String, v: VehicleProfile): Topic? =
        Topic.entries.filter { it.inMvp && repo.progress(memberId, it) == null }
            .firstOrNull { LearningCatalog.basis(it, v.id, v.powertrain, repo.entries(), repo.plans(v.id), today()) != null }

    private fun startLesson(c: Command.StartLesson) {
        val v = repo.vehicle(c.vehicleId) ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        if (!c.topic.inMvp) return emit(EngineEvent.Rejected(c, RejectReason.LESSON_NOT_AVAILABLE))
        val entries = repo.entries(); val plans = repo.plans(v.id)
        val basis = LearningCatalog.basis(c.topic, v.id, v.powertrain, entries, plans, today())
            ?: return emit(EngineEvent.Rejected(c, RejectReason.LESSON_NOT_AVAILABLE))
        val lesson = LearningCatalog.lesson(c.topic, basis, plans, entries, today())
            ?: return emit(EngineEvent.Rejected(c, RejectReason.LESSON_NOT_AVAILABLE))
        lessons[c.memberId to c.topic] = ActiveLesson(lesson)
        emit(EngineEvent.LessonReady(c.memberId, lesson))
    }

    private fun answerQuiz(c: Command.AnswerQuiz) {
        val active = lessons[c.memberId to c.topic] ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        val q = active.lesson.questions.firstOrNull { it.id == c.questionId } ?: return emit(EngineEvent.Rejected(c, RejectReason.NOT_FOUND))
        val correct = c.choice == q.answer
        if (correct) active.solved += q.id
        val complete = active.lesson.questions.all { it.id in active.solved }
        emit(EngineEvent.QuizFeedback(c.memberId, c.topic, q.id, correct, if (correct) null else q.hint, complete))
        if (!complete) return
        lessons.remove(c.memberId to c.topic)
        if (repo.progress(c.memberId, c.topic) != null) return   // 같은 주제는 도장 한 번
        val p = LearningProgress(c.memberId, c.topic, active.lesson.contentVersion, now(), active.lesson.basisEntryId)
        repo.saveProgress(p); emit(EngineEvent.StampAwarded(p))
    }

    /** 일지가 취소되면 그 일지를 근거로 받은 도장의 근거를 다른 유효 기록으로 옮기거나 연결을 해제한다. 학습 완료(도장)는 유지한다. */
    private fun recheckLearningBasis(cancelled: DiaryEntry) {
        val v = repo.vehicle(cancelled.vehicleId) ?: return
        for (p in repo.allProgress().filter { it.basisEntryId == cancelled.id }) {
            val nb = LearningCatalog.basis(p.topic, v.id, v.powertrain, repo.entries(), repo.plans(v.id), today())
            val up = p.copy(basisEntryId = nb?.id)
            repo.saveProgress(up); emit(EngineEvent.LearningBasisChanged(up))
        }
    }
}
