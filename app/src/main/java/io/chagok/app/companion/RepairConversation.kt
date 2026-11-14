package io.chagok.app.companion

import io.chagok.app.domain.*

/**
 * A confirmed visit and the named repair are the two answers needed for a diary entry.
 * A spoken item and cost are read back once before saving: recognition is not confirmation.
 */
internal class RepairConversation(private val diary: DiaryService) {
    private var visit: PlaceVisit? = null
    var choosing = false; private set
    /** Item and amount heard by voice, waiting for "네" before it becomes a confirmed entry. */
    private var pending: Pair<Item, SpokenIntent.Record?>? = null
    val confirming get() = pending != null
    val active get() = visit != null
    val visitId get() = visit?.id
    val question: String get() = pending?.let { (item, spoken) ->
        item.label + (spoken?.cost?.let { " · " + DiaryService.won(it) } ?: "") + "\n이대로 기록할까요?"
    } ?: if (choosing) "무엇을 정비하셨나요?" else "방금 정비하셨나요?"
    var hint = ""; private set

    fun begin(departed: PlaceVisit) {
        require(departed.place.kind == PlaceKind.REPAIR && departed.exited && departed.state == VisitState.READY)
        visit = departed
        choosing = false
        pending = null
        hint = "네 · 아니요 · 나중에"
    }

    fun clear() { visit = null; choosing = false; pending = null }

    fun receive(raw: String): String? {
        val current = visit ?: return null
        val answer = raw.trim().replace(Regex("[\\s.!?]"), "")
        val waiting = pending
        return when {
            answer in LATER -> { clear(); "확인할 기록에 남겼어요" }
            waiting != null -> when {
                answer in YES || answer in SAVE -> save(waiting.first, waiting.second)
                answer in NO -> { pending = null; hint = "정비 항목을 다시 말해주세요"; null }
                else -> {
                    // A corrected item or amount replaces the read-back; anything else asks again.
                    if (!choose(raw, answer)) hint = "네 · 아니요로 답해주세요"
                    null
                }
            }
            !choosing && answer in NO -> {
                diary.rejectVisit(current.id, current.user); clear(); "이 방문을 제외했어요"
            }
            !choosing -> {
                if (answer in YES) {
                    choosing = true; hint = "정비 항목을 말해주세요"
                }
                null
            }
            else -> {
                if (!choose(raw, answer)) hint = "예: 엔진오일 교환 · 드라이브 벨트 교체"
                null
            }
        }
    }

    private fun choose(raw: String, answer: String): Boolean {
        val record = UtteranceParser(diary::today).parse(raw) as? SpokenIntent.Record
        val item = repairs().firstOrNull { it.label.replace(" ", "") == answer } ?: record?.item
        if (item == null || item == Item.OTHER || item !in repairs()) return false
        pending = item to record
        hint = "네 · 아니요 · 나중에"
        return true
    }

    fun repairs() = diary.maintenanceItems() + Item.OTHER

    var saved: DiaryEntry? = null; private set
    fun takeSaved() = saved.also { saved = null }

    /** Saves a repair the user chose by touch, or one read back and accepted by voice. */
    fun save(item: Item, spoken: SpokenIntent.Record? = null): String {
        val current = requireNotNull(visit)
        require(choosing && item in repairs())
        val draft = diary.visitDraft(current.id, current.user, item)
        if (spoken?.cost != null || !spoken?.wheels.isNullOrEmpty()) {
            diary.updateEntry(draft.id, draft.performedOn, draft.odometer?.value, spoken?.cost ?: draft.cost, null,
                draft.memo, spoken?.wheels.orEmpty().ifEmpty { draft.wheels })
        }
        val result = diary.confirm(draft.id)
        clear()
        return when (result) {
            is DiaryService.ConfirmResult.Saved -> { saved = result.entry; "${item.label} 기록을 저장했어요" }
            else -> "정차 후 확인할 초안으로 남겼어요"
        }
    }

    private companion object {
        val LATER = listOf("나중에", "나중", "취소", "그만")
        val YES = listOf("네", "예", "응", "맞아", "맞아요", "했어", "했어요", "정비했어요")
        val SAVE = listOf("기록해", "기록해줘", "기록해주세요", "저장해", "저장해줘", "저장해주세요", "좋아", "좋아요")
        val NO = listOf("아니", "아니요", "아뇨", "안했어", "안했어요", "정비안했어요")
    }
}
