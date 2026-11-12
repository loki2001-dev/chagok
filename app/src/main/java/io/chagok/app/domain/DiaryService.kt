package io.chagok.app.domain

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 차량 값 하나를 읽은 결과. 미수신은 0이 아니라 [Unavailable]이다 */
sealed interface Reading {
    data class Value(val observation: Observation) : Reading
    data class Unavailable(val reason: String) : Reading
}

/**
 * 일지 자동화의 중심 (기획서 3장 메인 흐름, 5장 자동화 규칙).
 * 차량 관측, 사용자 확인, 앱 계산을 구분해서 저장한다. 사람이 확인하기 전에는 아무것도 확정하지 않는다.
 */
class DiaryService(
    private val store: DiaryStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val zone: ZoneId get() = clock.zone
    fun today(): LocalDate = LocalDate.now(clock)
    fun now(): Instant = clock.instant()

    private val parser = UtteranceParser(::today)
    private val trackers = mutableMapOf<EnergyKind, EnergyTracker>()

    // ---------------------------------------------------------------- 차량 프로필

    fun profile(): VehicleProfile =
        store.profile() ?: VehicleProfile(id = newId(), name = "우리 차", powertrain = Powertrain.ICE).also(store::saveProfile)

    fun updateProfile(name: String, powertrain: Powertrain, model: String?) {
        store.saveProfile(profile().copy(name = name, powertrain = powertrain, model = model))
    }

    /** VIN은 선택적 식별 보조다. 이미 다른 VIN이 있으면 바꾸지 않고 false를 돌려준다 (자동 병합 금지) */
    fun attachVin(vin: String): Boolean {
        val p = profile()
        if (p.vin != null && p.vin != vin) return false
        store.saveProfile(p.copy(vin = vin))
        return true
    }

    fun items(): List<Item> = Item.entries.filter { it.availableFor(profile().powertrain) }

    // ---------------------------------------------------------------- 음성 → 초안

    sealed interface VoiceOutcome {
        data class Drafted(val draft: DiaryEntry, val checks: List<String>) : VoiceOutcome
        data class Corrected(val draft: DiaryEntry, val previousCost: Long?) : VoiceOutcome
        data class Cancelled(val draftId: String) : VoiceOutcome
        data class Next(val statuses: List<ScheduleStatus>) : VoiceOutcome

        /** 대상이 모호하거나 해석하지 못했다. 저장하지 않는다 */
        data class Ask(val message: String) : VoiceOutcome
    }

    /**
     * STT 최종 텍스트를 해석한다.
     * @param odometer 지금 차량에서 읽은 누적 주행거리. 미수신이면 [Reading.Unavailable]
     * @param activeDraftId 화면에 열려 있는 초안. 수정·취소 발화의 대상이다
     */
    fun handleVoice(text: String, odometer: Reading, author: String, activeDraftId: String?): VoiceOutcome {
        val active = activeDraftId?.let(store::entry)?.takeIf { it.status == EntryStatus.DRAFT }
        return when (val intent = parser.parse(text)) {
            is SpokenIntent.Record -> draft(intent, odometer, author, text)
            is SpokenIntent.CorrectCost -> {
                if (active == null) return VoiceOutcome.Ask("어느 기록의 금액을 고칠지 먼저 선택해 주세요.")
                val updated = active.copy(cost = intent.cost)
                store.saveEntry(updated)
                VoiceOutcome.Corrected(updated, active.cost)
            }
            SpokenIntent.Cancel -> {
                if (active == null) return VoiceOutcome.Ask("취소할 초안이 없어요. 저장된 기록은 일지 화면에서 취소해 주세요.")
                store.deleteEntry(active.id)
                VoiceOutcome.Cancelled(active.id)
            }
            SpokenIntent.QueryNext -> VoiceOutcome.Next(schedules(currentKm(odometer)))
            is SpokenIntent.Unknown -> VoiceOutcome.Ask("무엇을 했는지 알아듣지 못했어요. 예: \"오늘 엔진오일 갈았어. 8만 원\"")
        }
    }

    private fun draft(intent: SpokenIntent.Record, odometer: Reading, author: String, text: String): VoiceOutcome {
        val item = intent.item ?: Item.OTHER
        val p = profile()
        if (!item.availableFor(p.powertrain)) {
            return VoiceOutcome.Ask("${p.powertrain.label} 프로필에는 '${item.label}' 항목이 없어요.")
        }
        val checks = mutableListOf<String>()
        val date = intent.date

        when (date.kind) {
            SpokenDate.Kind.UNSPOKEN -> checks += "날짜를 말하지 않아 오늘로 두었어요. 정비 직후인지 확인해 주세요."
            SpokenDate.Kind.APPROXIMATE -> checks += "날짜가 대략이에요. 실제로 정비한 날을 골라 주세요."
            else -> Unit
        }

        // 오늘 한 정비에만 지금 받은 km를 붙인다. 과거 정비에 오늘 값을 자동 확정하지 않는다
        val fresh = freshObservation(odometer)
        val evidence = if (date.isToday && fresh != null) {
            checks += "차량에서 받은 주행거리 ${km(fresh.value)}를 붙였어요 (수신 ${time(fresh.receivedAt)})."
            FieldEvidence(fresh.value, "km", Source.VEHICLE, fresh.receivedAt, fresh)
        } else {
            if (!date.isToday) {
                checks += "지난 정비예요. 당시 주행거리를 입력해 주세요." +
                    (fresh?.let { " 현재 ${km(it.value)}는 참고값이에요." } ?: "")
            } else {
                checks += "주행거리를 받지 못했어요(${(odometer as? Reading.Unavailable)?.reason ?: "오래된 값"}). " +
                    "직접 입력하거나 비워 두면 거리 조건은 계산을 미뤄요."
            }
            null
        }

        if (intent.cost == null && item.kind != ItemKind.REFILL && item != Item.SYMPTOM_NOTE) {
            checks += "비용을 말하지 않았어요. 비워 두면 0원이 아니라 '미입력'으로 남아요."
        }
        when (item) {
            Item.TIRE_REPLACE -> checks += if (intent.wheels.isEmpty()) {
                "교체한 바퀴 위치와 수량을 골라 주세요."
            } else {
                "교체한 바퀴: ${intent.wheels.joinToString { it.label }}. 위치 교환 일정은 따로 관리해요."
            }
            Item.TIRE_ROTATION -> checks += "새 타이어 교체가 아니라 위치 교환이 맞나요?"
            Item.WASHER_REFILL -> checks += "보충 기록은 차량의 워셔액 경고가 꺼졌다는 뜻이 아니에요."
            Item.SYMPTOM_NOTE -> checks += "증상 메모는 고장 진단이 아니에요. 정비소 방문 때 함께 보여 줄 수 있어요."
            else -> Unit
        }

        var candidateId: String? = null
        if (item.kind == ItemKind.ENERGY) {
            val kind = if (item == Item.REFUEL) EnergyKind.FUEL else EnergyKind.SOC
            val candidate = pendingCandidates().firstOrNull { it.kind == kind }
            if (candidate != null) {
                candidateId = candidate.id
                checks += "자동 기록 후보(${CandidateRules.describe(candidate)})와 연결해요. 맞는지 확인해 주세요."
            } else {
                checks += "연결할 후보가 없어요. 수동 기록으로 저장돼요."
            }
            if (item == Item.CHARGE) checks += "충전량(kWh)과 비용은 직접 입력해요. SoC에서 추정하지 않아요."
        }

        val entry = DiaryEntry(
            id = newId(),
            vehicleId = p.id,
            item = item,
            performedOn = date.date,
            createdAt = now(),
            odometer = evidence,
            cost = intent.cost,
            status = EntryStatus.DRAFT,
            author = author,
            wheels = intent.wheels,
            candidateId = candidateId,
            spokenText = text,
            memo = intent.memo,
        )
        store.saveEntry(entry)
        return VoiceOutcome.Drafted(entry, checks)
    }

    /**
     * 화면에서 직접 쓰는 초안 (STT 실패 시 정차 중 수동 입력 경로).
     * 오늘 기록이고 차량 값이 새것이면 차량 출처로 붙인다. 과거 기록에는 붙이지 않는다.
     */
    fun manualDraft(item: Item, date: LocalDate, cost: Long?, odometer: Reading, author: String): DiaryEntry {
        val fresh = freshObservation(odometer)?.takeIf { date == today() }
        val entry = DiaryEntry(
            id = newId(),
            vehicleId = profile().id,
            item = item,
            performedOn = date,
            createdAt = now(),
            odometer = fresh?.let { FieldEvidence(it.value, "km", Source.VEHICLE, it.receivedAt, it) },
            cost = cost,
            status = EntryStatus.DRAFT,
            author = author,
        )
        store.saveEntry(entry)
        return entry
    }

    // ---------------------------------------------------------------- 수정·확정·취소

    fun entry(id: String): DiaryEntry? = store.entry(id)

    fun setCost(id: String, cost: Long?) = edit(id, "비용 ${won(store.entry(id)?.cost)} → ${won(cost)}") { it.copy(cost = cost) }

    fun setDate(id: String, date: LocalDate) = edit(id, "날짜 → $date") { it.copy(performedOn = date) }

    fun setWheels(id: String, wheels: Set<Wheel>) = edit(id, "바퀴 → ${wheels.joinToString { w -> w.label }}") { it.copy(wheels = wheels) }

    /** 사용자가 km를 입력·수정하면 출처는 사용자 값이 되고, 원래 차량 관측값은 남겨 둔다 */
    fun setOdometer(id: String, km: Double?) = edit(id, "주행거리 → ${km?.let(::km) ?: "미입력"}") {
        val observed = it.odometer?.observed
        it.copy(odometer = km?.let { v -> FieldEvidence(v, "km", Source.USER, observed = observed) })
    }

    private fun edit(id: String, summary: String, change: (DiaryEntry) -> DiaryEntry): DiaryEntry? {
        val current = store.entry(id) ?: return null
        if (current.status == EntryStatus.CANCELLED) return current
        var updated = change(current)
        if (current.status == EntryStatus.CONFIRMED) {
            // 저장 후 수정은 변경 이력을 보존한다
            updated = updated.copy(
                version = current.version + 1,
                history = current.history + Revision(current.version + 1, now(), summary),
            )
        }
        store.saveEntry(updated)
        return updated
    }

    sealed interface ConfirmResult {
        data class Saved(val entry: DiaryEntry, val warnings: List<String>) : ConfirmResult

        /** 같은 날 같은 항목의 확정 기록이 이미 있다. 다시 확인하면 [allowDuplicate]로 저장한다 */
        data class PossibleDuplicate(val existing: DiaryEntry) : ConfirmResult
        data class Rejected(val reason: String) : ConfirmResult
    }

    fun confirm(id: String, allowDuplicate: Boolean = false): ConfirmResult {
        val draft = store.entry(id) ?: return ConfirmResult.Rejected("초안을 찾을 수 없어요.")
        if (draft.status != EntryStatus.DRAFT) return ConfirmResult.Rejected("이미 ${draft.status.label}된 기록이에요.")
        if (draft.performedOn.isAfter(today())) return ConfirmResult.Rejected("미래 날짜에는 정비 기록을 저장할 수 없어요.")

        val confirmed = confirmedEntries()
        if (!allowDuplicate) {
            confirmed.firstOrNull { it.item == draft.item && it.performedOn == draft.performedOn }
                ?.let { return ConfirmResult.PossibleDuplicate(it) }
        }

        val warnings = mutableListOf<String>()
        val km = draft.odometer?.value
        if (km != null) {
            val earlierHigher = confirmed.firstOrNull { e ->
                val other = e.odometer?.value
                other != null && !e.performedOn.isAfter(draft.performedOn) && other > km
            }
            if (earlierHigher != null) {
                warnings += "이전 기록(${earlierHigher.performedOn}, ${km(earlierHigher.odometer!!.value)})보다 주행거리가 작아요. 값을 다시 확인해 주세요."
            }
        }

        val candidate = draft.candidateId?.let { cid -> store.candidates(draft.vehicleId).firstOrNull { it.id == cid } }
        if (candidate != null && candidate.state == CandidateState.CONFIRMED && candidate.linkedEntryId != draft.id) {
            return ConfirmResult.Rejected("이 후보는 이미 다른 기록으로 확정됐어요.")
        }

        val saved = draft.copy(status = EntryStatus.CONFIRMED)
        store.saveEntry(saved)
        candidate?.let { store.saveCandidate(it.copy(state = CandidateState.CONFIRMED, linkedEntryId = saved.id)) }
        return ConfirmResult.Saved(saved, warnings)
    }

    /** 초안이면 버리고, 확정 기록이면 취소 상태로 남긴다. 비용·일정·도장에서 빠진다 */
    fun cancel(id: String) {
        val entry = store.entry(id) ?: return
        when (entry.status) {
            EntryStatus.DRAFT -> store.deleteEntry(id)
            EntryStatus.CANCELLED -> return
            EntryStatus.CONFIRMED -> {
                store.saveEntry(
                    entry.copy(
                        status = EntryStatus.CANCELLED,
                        version = entry.version + 1,
                        history = entry.history + Revision(entry.version + 1, now(), "취소"),
                    ),
                )
                entry.candidateId?.let { cid ->
                    store.candidates(entry.vehicleId).firstOrNull { it.id == cid }?.let {
                        store.saveCandidate(it.copy(state = CandidateState.PENDING, linkedEntryId = null))
                    }
                }
                reviewStampsAfterCancel(entry)
            }
        }
    }

    fun confirmedEntries(): List<DiaryEntry> =
        store.entries(profile().id).filter { it.status == EntryStatus.CONFIRMED }

    fun drafts(): List<DiaryEntry> = store.entries(profile().id).filter { it.status == EntryStatus.DRAFT }

    /** 타임라인: 확정·취소 기록, 최근 순 */
    fun timeline(): List<DiaryEntry> =
        store.entries(profile().id)
            .filter { it.status != EntryStatus.DRAFT }
            .sortedWith(compareByDescending<DiaryEntry> { it.performedOn }.thenByDescending { it.createdAt })

    fun ledger(month: java.time.YearMonth) = Ledger.month(store.entries(profile().id), month)

    // ---------------------------------------------------------------- 관리 일정

    fun setPlan(item: Item, intervalKm: Int?, intervalMonths: Int?, source: String) {
        require(intervalKm == null || intervalKm > 0) { "주기 거리는 0보다 커야 해요" }
        require(intervalMonths == null || intervalMonths > 0) { "주기 개월은 0보다 커야 해요" }
        val existing = store.plans(profile().id).firstOrNull { it.item == item }
        store.savePlan(
            MaintenancePlan(
                vehicleId = profile().id,
                item = item,
                intervalKm = intervalKm,
                intervalMonths = intervalMonths,
                source = source.ifBlank { "출처 미입력" },
                setOn = today(),
                notificationsOn = existing?.notificationsOn ?: true,
            ),
        )
    }

    fun snooze(item: Item, until: LocalDate) {
        store.plans(profile().id).firstOrNull { it.item == item }?.let { store.savePlan(it.copy(snoozedUntil = until)) }
    }

    fun setNotifications(item: Item, on: Boolean) {
        store.plans(profile().id).firstOrNull { it.item == item }?.let { store.savePlan(it.copy(notificationsOn = on)) }
    }

    /** 급한 순서: 도래 → 임박 → 예정 → 기록 없음 → 주기 미설정 */
    fun schedules(currentKm: Double?): List<ScheduleStatus> {
        val entries = store.entries(profile().id)
        val order = listOf(ScheduleState.DUE, ScheduleState.SOON, ScheduleState.UPCOMING, ScheduleState.NO_RECORD, ScheduleState.NO_PLAN)
        return store.plans(profile().id)
            .filter { it.item.availableFor(profile().powertrain) }
            .map { ScheduleCalculator.evaluate(it, entries, currentKm, today()) }
            .sortedWith(compareBy<ScheduleStatus> { order.indexOf(it.state) }.thenBy { it.remainingDays ?: Long.MAX_VALUE })
    }

    /** 정차 브리핑에 올릴 항목. 같은 항목은 한 카드, 미루거나 끈 항목은 뺀다 */
    fun briefing(currentKm: Double?): List<ScheduleStatus> =
        schedules(currentKm).filter {
            (it.state == ScheduleState.DUE || it.state == ScheduleState.SOON) &&
                it.plan.notificationsOn &&
                (it.plan.snoozedUntil == null || today().isAfter(it.plan.snoozedUntil))
        }

    fun currentKm(reading: Reading): Double? = freshObservation(reading)?.value

    // ---------------------------------------------------------------- 주유·충전 후보

    fun energyKinds(): List<EnergyKind> = when (profile().powertrain) {
        Powertrain.ICE, Powertrain.HYBRID -> listOf(EnergyKind.FUEL)
        Powertrain.EV -> listOf(EnergyKind.SOC)
    }

    /** 연료·SoC 관측을 넣는다. 새 후보가 생기면 돌려준다 */
    fun observeEnergy(kind: EnergyKind, value: Double?, parked: Boolean): DetectionCandidate? {
        if (kind !in energyKinds()) return null
        val vid = profile().id
        val tracker = trackers.getOrPut(kind) { EnergyTracker(kind).also { it.restore(store.energyBaseline(vid, kind)) } }
        return when (val r = tracker.feed(value, now(), parked)) {
            EnergyTracker.Result.None -> null
            is EnergyTracker.Result.BaselineMoved -> {
                store.saveEnergyBaseline(vid, kind, r.baseline)
                null
            }
            is EnergyTracker.Result.Rise -> {
                store.saveEnergyBaseline(vid, kind, r.after)
                if (CandidateRules.isDuplicate(r, kind, store.candidates(vid))) return null
                DetectionCandidate(newId(), vid, kind, r.before, r.after, CandidateState.PENDING, r.gapLong)
                    .also(store::saveCandidate)
            }
        }
    }

    fun pendingCandidates(): List<DetectionCandidate> =
        store.candidates(profile().id).filter { it.state == CandidateState.PENDING }.sortedByDescending { it.after.receivedAt }

    /** 후보를 확인해 일지 초안으로 만든다. 금액·날짜는 사용자가 확인한 뒤 [confirm]으로 저장한다 */
    fun draftFromCandidate(id: String, author: String, odometer: Reading): DiaryEntry? {
        val c = pendingCandidates().firstOrNull { it.id == id } ?: return null
        val item = if (c.kind == EnergyKind.FUEL) Item.REFUEL else Item.CHARGE
        val fresh = freshObservation(odometer)
            ?.takeIf { Duration.between(c.after.receivedAt, it.receivedAt).abs() <= FRESH_LIMIT }
        val entry = DiaryEntry(
            id = newId(),
            vehicleId = c.vehicleId,
            item = item,
            performedOn = c.after.receivedAt.atZone(zone).toLocalDate(),
            createdAt = now(),
            odometer = fresh?.let { FieldEvidence(it.value, "km", Source.VEHICLE, it.receivedAt, it) },
            cost = null,
            status = EntryStatus.DRAFT,
            author = author,
            candidateId = c.id,
        )
        store.saveEntry(entry)
        return entry
    }

    fun excludeCandidate(id: String) {
        store.candidates(profile().id).firstOrNull { it.id == id && it.state == CandidateState.PENDING }
            ?.let { store.saveCandidate(it.copy(state = CandidateState.EXCLUDED)) }
    }

    // ---------------------------------------------------------------- 관리 도감

    fun stamps(user: String): List<LearningProgress> = store.progress(user)

    /** 상황에 맞는 미션 한 개만 추천한다. 이미 도장을 받은 주제는 다시 추천하지 않는다 */
    fun recommendedMission(user: String, currentKm: Double?): Mission? = missions(user, currentKm).firstOrNull()

    fun missions(user: String, currentKm: Double?): List<Mission> {
        val done = store.progress(user).filter { !it.needsReview }.map { it.topic }.toSet()
        val result = mutableListOf<Mission>()
        if (Topic.FIRST_DIARY !in done) {
            confirmedEntries().minByOrNull { it.createdAt }?.let { result += LearningCatalog.firstDiary(it) }
        }
        if (Topic.READ_INTERVAL !in done) {
            schedules(currentKm).firstNotNullOfOrNull { LearningCatalog.readInterval(it) }?.let { result += it }
        }
        return result
    }

    data class AnswerResult(val correct: Boolean, val hint: String?, val stamped: Boolean)

    fun answer(user: String, mission: Mission, choice: Int): AnswerResult {
        if (choice != mission.answerIndex) return AnswerResult(false, mission.hint, false)
        val existing = store.progress(user).firstOrNull { it.topic == mission.topic }
        if (existing != null && !existing.needsReview) return AnswerResult(true, null, false)
        val evidence = store.entry(mission.evidenceEntryId)
        if (evidence == null || evidence.status != EntryStatus.CONFIRMED) return AnswerResult(true, null, false)
        store.saveProgress(LearningProgress(user, mission.topic, LearningCatalog.CONTENT_VERSION, now(), evidence.id))
        return AnswerResult(true, null, true)
    }

    private fun reviewStampsAfterCancel(cancelled: DiaryEntry) {
        val users = (store.progress(cancelled.author) + allProgressFor(cancelled.id)).distinct()
        for (p in users.filter { it.evidenceEntryId == cancelled.id }) {
            val alternative = when (p.topic) {
                Topic.FIRST_DIARY -> confirmedEntries().minByOrNull { it.createdAt }
                Topic.READ_INTERVAL -> schedules(null).firstNotNullOfOrNull { s ->
                    s.basis?.takeIf { s.plan.intervalKm != null && s.plan.intervalMonths != null }
                }
                else -> null
            }
            store.saveProgress(
                if (alternative != null) p.copy(evidenceEntryId = alternative.id, needsReview = false) else p.copy(needsReview = true),
            )
        }
    }

    private val knownUsers = mutableSetOf<String>()

    /** 공용 일지 사용자 목록 (도장 근거 재검토용). 화면에서 사용자를 고를 때 등록한다 */
    fun registerUser(user: String) {
        knownUsers += user
    }

    private fun allProgressFor(entryId: String): List<LearningProgress> =
        knownUsers.flatMap { store.progress(it) }.filter { it.evidenceEntryId == entryId }

    // ---------------------------------------------------------------- 공용 일지 · 리포트

    /** 다른 사람이 마지막 확인 이후 새로 확정한 기록 (기획서 1장: 인수인계는 공용 일지의 변경 사항 확인) */
    fun changesSince(user: String): List<DiaryEntry> {
        val since = store.lastSeen(user)
        return confirmedEntries().filter { it.author != user && (since == null || it.createdAt.isAfter(since)) }
    }

    fun markSeen(user: String) = store.saveLastSeen(user, now())

    /** 정비 리포트. 선택한 날짜·항목만 담고 VIN·개인 프로필은 넣지 않는다 */
    fun report(from: LocalDate, to: LocalDate, items: Set<Item>?, includeMemos: Boolean): String {
        val rows = confirmedEntries()
            .filter { !it.performedOn.isBefore(from) && !it.performedOn.isAfter(to) && (items == null || it.item in items) }
            .sortedBy { it.performedOn }
        return buildString {
            appendLine("차곡 정비 리포트 · ${profile().model ?: profile().name} · $from ~ $to")
            appendLine("사용자가 확인한 기록이며, 정비소가 인증한 공식 이력이 아닙니다.")
            appendLine()
            rows.forEach { e ->
                append("${e.performedOn} · ${e.item.label}")
                e.odometer?.let { append(" · ${km(it.value)}(${it.source.label})") }
                append(" · ${won(e.cost)}")
                if (e.wheels.isNotEmpty()) append(" · ${e.wheels.joinToString { it.label }}")
                appendLine()
                if (includeMemos && e.memo != null) appendLine("  메모: ${e.memo}")
            }
            if (rows.isEmpty()) appendLine("해당 기간의 확정 기록이 없습니다.")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun freshObservation(reading: Reading): Observation? {
        val obs = (reading as? Reading.Value)?.observation ?: return null
        return obs.takeIf { Duration.between(it.receivedAt, now()) <= FRESH_LIMIT }
    }

    private fun time(at: Instant) = TIME.format(at.atZone(zone))

    companion object {
        /** 이보다 오래된 차량 값은 자동으로 붙이지 않는다 */
        val FRESH_LIMIT: Duration = Duration.ofMinutes(10)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        fun km(v: Double) = "%,.0fkm".format(v)
        fun won(v: Long?) = v?.let { "%,d원".format(it) } ?: "금액 미입력"
    }
}
