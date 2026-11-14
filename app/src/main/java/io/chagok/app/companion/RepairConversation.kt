package io.chagok.app.companion

import io.chagok.app.domain.*

/** A confirmed visit and the named repair are the two answers needed for a diary entry. */
internal class RepairConversation(private val diary: DiaryService) {
    private var visit: PlaceVisit? = null
    var choosing = false; private set
    val active get() = visit != null
    val question get() = if (choosing) "무엇을 정비하셨나요?" else "방금 정비하셨나요?"
    var hint = ""; private set

    fun begin(departed: PlaceVisit) {
        require(departed.place.kind == PlaceKind.REPAIR && departed.exited && departed.state == VisitState.READY)
        visit = departed
        choosing = false
        hint = "네 · 아니요 · 나중에"
    }

    fun clear() { visit = null; choosing = false }

    fun receive(raw: String): String? {
        val current = visit ?: return null
        val answer = raw.trim().replace(Regex("[\\s.!?]"), "")
        return when {
            answer in listOf("나중에", "나중", "취소", "그만") -> { clear(); "확인할 기록에 남겼어요" }
            !choosing && answer in listOf("아니", "아니요", "아뇨", "안했어", "안했어요", "정비안했어요") -> {
                diary.rejectVisit(current.id, current.user); clear(); "이 방문을 제외했어요"
            }
            !choosing -> {
                if (answer in listOf("네","예","응","맞아","맞아요","했어","했어요","정비했어요")) {
                    choosing = true; hint = "정비 항목을 말해주세요"
                }
                null
            }
            else -> {
                val record = UtteranceParser(diary::today).parse(raw) as? SpokenIntent.Record
                val item = repairs().firstOrNull { it.label.replace(" ", "") == answer } ?: record?.item
                if (item == null || item == Item.OTHER || item !in repairs()) {
                    hint = "예: 엔진오일 교환 · 드라이브 벨트 교체"
                    null
                } else save(item, record)
            }
        }
    }

    fun repairs() = diary.maintenanceItems() + Item.OTHER

    var saved: DiaryEntry? = null; private set
    fun takeSaved() = saved.also { saved = null }

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
}
