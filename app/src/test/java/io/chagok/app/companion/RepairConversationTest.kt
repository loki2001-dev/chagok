package io.chagok.app.companion

import io.chagok.app.domain.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RepairConversationTest {
    private val store = MemoryStore()
    private val diary = DiaryService(store).apply { selectUser("나") }
    private val place = ServicePlace("demo", "시험 정비소", PlaceKind.REPAIR, 37.5, 127.0, "", "test")
    private val now = Instant.now()
    private val visit = PlaceVisit("visit", diary.profile().id, "나", place, now, now, null,
        VisitState.READY, observedSeconds = 60, exited = true)
    private val conversation = RepairConversation(diary)

    @Test fun asksOnlyAfterDepartureAndSavesWithoutAskingCost() {
        assertTrue(runCatching { conversation.begin(visit.copy(exited = false)) }.isFailure)
        store.saveVisit(visit)
        conversation.begin(visit)
        conversation.receive("네")
        conversation.receive("모르겠어요")
        assertTrue(conversation.choosing)
        assertTrue(diary.drafts().isEmpty())
        conversation.receive("에어컨 필터 교체")
        val entry = diary.confirmedEntries().single()
        assertNull(entry.cost)
        assertEquals(Item.CABIN_FILTER, entry.item)
        assertEquals(entry, conversation.takeSaved())
        assertNull(conversation.takeSaved())
        conversation.receive("에어컨 필터 교체")
        assertEquals(1, diary.confirmedEntries().size)
    }

    @Test fun deferredVisitRemainsReviewableAndDeclinedVisitIsExcluded() {
        store.saveVisit(visit)
        conversation.begin(visit)
        conversation.receive("나중에")
        assertEquals(listOf(visit), diary.pendingVisits("나"))
        conversation.begin(visit)
        conversation.receive("아니요")
        assertTrue(diary.pendingVisits("나").isEmpty())
        assertTrue(diary.confirmedEntries().isEmpty())
    }
}
