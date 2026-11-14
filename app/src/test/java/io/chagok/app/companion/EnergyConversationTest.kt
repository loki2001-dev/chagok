package io.chagok.app.companion

import io.chagok.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.YearMonth

class EnergyConversationTest {
    private val store=MemoryStore()
    private val diary=DiaryService(store)
    private val c=EnergyConversation(diary)
    private fun begin(id:String="candidate",user:String="나") {
        diary.updateProfile("EV",Powertrain.EV,null)
        val at=Instant.now()
        store.saveCandidate(DetectionCandidate(id,diary.profile().id,EnergyKind.SOC,
            Observation(20.0,at.minusSeconds(60)),Observation(80.0,at),CandidateState.PENDING,false))
        assertTrue(c.begin(id,user,Reading.Unavailable("test")))
    }
    @Test fun explicitAmountAndSecondConfirmationAreRequiredForLedger() {
        begin();c.receive("맞아");assertEquals(EnergyConversation.Stage.AMOUNT,c.stage)
        c.receive("5만 원");assertEquals(EnergyConversation.Stage.CONFIRM,c.stage)
        assertEquals(0L,diary.ledger(YearMonth.now()).total)
        c.receive("맞아");assertEquals(EnergyConversation.Stage.FINISHED,c.stage)
        assertEquals(50000L,diary.ledger(YearMonth.now()).total)
        c.receive("맞아");assertEquals(1,diary.confirmedEntries().size)
    }
    @Test fun deferringKeepsAmountAsDraftAndResumeDoesNotDuplicate() {
        begin();val id=c.draftId;c.receive("12000원");c.defer()
        assertEquals(EntryStatus.DRAFT,diary.entry(id!!)!!.status);assertEquals(0L,diary.ledger(YearMonth.now()).total)
        c.clear();assertTrue(c.begin("candidate","나",Reading.Unavailable("test")));assertEquals(id,c.draftId)
        c.receive("아니요");assertEquals(EnergyConversation.Stage.FINISHED,c.stage);assertEquals(1,diary.drafts().size)
    }
    @Test fun correctionAndAmbiguousNumbersCannotSilentlySaveWrongAmount() {
        begin();c.receive("5만 원 6만 원");assertEquals(EnergyConversation.Stage.AMOUNT,c.stage)
        c.receive("5만 원이 아니라 6만 원");assertEquals(60000L,diary.entry(c.draftId!!)!!.cost)
        c.receive("7만 원");assertEquals(70000L,diary.entry(c.draftId!!)!!.cost)
        assertTrue(diary.confirmedEntries().isEmpty());c.receive("저장해줘");assertEquals(70000L,diary.ledger(YearMonth.now()).total)
    }
    @Test fun missingCandidateAndDuplicateExpenseNeverForceSave() {
        assertFalse(c.begin("missing","나",Reading.Unavailable("test")))
        begin("first");c.receive("10000원");c.receive("네")
        c.clear();begin("second");c.receive("10000원");c.receive("네")
        assertEquals(1,diary.confirmedEntries().size);assertEquals(1,diary.drafts().size)
        assertTrue(c.text.contains("같은 날"))
    }
}
