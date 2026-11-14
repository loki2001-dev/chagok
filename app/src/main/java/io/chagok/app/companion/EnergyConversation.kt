package io.chagok.app.companion

import io.chagok.app.domain.*

/** A short amount -> explicit confirmation conversation; silence never confirms an expense. */
class EnergyConversation(private val diary: DiaryService) {
    enum class Stage { IDLE, AMOUNT, CONFIRM, FINISHED }
    var stage = Stage.IDLE; private set
    var draftId: String? = null; private set
    var text = ""; private set
    var hint = ""; private set
    private var candidateId: String? = null
    private var user = "나"
    val asking get() = stage == Stage.AMOUNT || stage == Stage.CONFIRM
    fun begin(candidateId: String, user: String, odometer: Reading): Boolean {
        diary.selectUser(user)
        val draft = diary.draftFromCandidate(candidateId, user, odometer) ?: return false
        this.candidateId=candidateId;this.user=user;diary.questionQueue(user).offered(candidateId)
        draftId = draft.id; stage = if(draft.cost!=null||diary.costWasUnknown(draft.id))Stage.CONFIRM else Stage.AMOUNT
        text = if (draft.item == Item.CHARGE) "충전비,\n얼마였어요?" else "주유비,\n얼마였어요?"
        if(draft.cost==null&&diary.costWasUnknown(draft.id))text="금액 없이\n이어서 기록할까요?"
        if(draft.cost!=null)text="${DiaryService.won(draft.cost)},\n이어서 기록할까요?"
        hint = if(stage==Stage.CONFIRM)"‘맞아’ 또는 금액 수정" else "금액 · ‘금액 몰라’ · ‘나중에’"; return true
    }
    fun defer() { if (!asking) return; stage = Stage.FINISHED; text = "초안으로\n남겨둘게요."; hint = "확인 전에는 비용에 더하지 않아요" }
    fun receive(raw: String) {
        if (!asking) return
        val normalized = raw.trim().replace(Regex("[\\s.!?]"), "")
        if(normalized.contains("오늘")&&(normalized.contains("묻지")||normalized.contains("그만"))) {
            diary.questionQueue(user).muteToday();defer();text="오늘은 더 묻지 않을게요.";return
        }
        if(listOf("충전한게아니", "충전한거아니", "충전안했", "주유한게아니", "주유한거아니", "주유안했", "잘못감지", "기름안넣").any { normalized.contains(it) }) {
            candidateId?.let(diary::rejectCandidate);stage=Stage.FINISHED;text="이 후보는 제외했어요.";hint="비용에 더하지 않아요";return
        }
        if (normalized.contains("나중") || normalized in listOf("취소", "취소해", "그만", "아니", "아니요")) { defer(); return }
        val draft = draftId?.let(diary::entry)?.takeIf { it.status == EntryStatus.DRAFT }
        if (draft == null) { defer(); return }
        if (stage == Stage.CONFIRM && normalized in listOf("네", "응", "맞아", "맞아요", "저장", "저장해", "저장해줘", "확인")) {
            val result = diary.confirm(draft.id)
            stage = Stage.FINISHED
            text = when (result) {
                is DiaryService.ConfirmResult.Saved -> if(result.entry.cost==null)"금액 없이\n기록했어요." else "${DiaryService.won(result.entry.cost)}\n기록했어요."
                is DiaryService.ConfirmResult.PossibleDuplicate -> "같은 날 기록이 있어요.\n정차 후 확인해 주세요."
                is DiaryService.ConfirmResult.Rejected -> "확인이 필요해요.\n초안으로 남겼어요."
            }
            hint = if (result is DiaryService.ConfirmResult.Saved) "이제 편하게 달려요" else "자동으로 중복 저장하지 않아요"
            return
        }
        val unknown=normalized.contains("금액없이") || ((normalized.contains("몰라")||normalized.contains("모르겠"))&&listOf("금액","가격","얼마","비용").any { normalized.contains(it) })
        val free=normalized.contains("무료")||normalized.contains("공짜")
        if(unknown||free) {
            diary.setCost(draft.id,if(free)0L else null);diary.rememberUnknownCost(draft.id,!free);stage=Stage.CONFIRM
            text=if(free)"무료로 기록할까요?" else "금액 없이 기록할까요?"
            hint="‘맞아’라고 하면 저장해요";return
        }
        val amounts = UtteranceParser.amounts(raw)
        val amount = when { normalized.contains("아니라") -> amounts.lastOrNull(); amounts.size == 1 -> amounts.single(); else -> null }
        if (amount == null || amount > 100_000_000L) { hint = "‘5만 원’처럼 금액을 알려주세요"; return }
        diary.rememberUnknownCost(draft.id,false)
        diary.updateEntry(draft.id, draft.performedOn, draft.odometer?.value, amount, draft.energyAmount, draft.memo, draft.wheels)
        stage = Stage.CONFIRM; text = "${DiaryService.won(amount)},\n기록할까요?"; hint = "‘맞아’ 또는 ‘나중에’라고 말해주세요"
    }
    fun clear() { stage = Stage.IDLE; draftId = null; text = ""; hint = "" }
}
