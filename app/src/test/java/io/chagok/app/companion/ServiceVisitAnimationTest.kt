package io.chagok.app.companion

import io.chagok.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ServiceVisitAnimationTest {
    private val parked = MotionSnapshot(speedMps = 0f, restricted = false, animationAllowed = true, parkGear = true)
    private val now = Instant.parse("2026-11-14T16:00:00Z")
    private val place = ServicePlace("shop", "합성 정비소", PlaceKind.REPAIR, 37.5, 127.0, "", "test")
    private val visit = PlaceVisit("visit", "car", "나", place, now.minusSeconds(60), now, null, VisitState.READY)

    @Test fun `both services walk up work and leave without looping`() {
        for (kind in PlaceKind.entries) {
            val animation = ServiceVisitAnimation()
            assertTrue(animation.begin("visit", kind))
            assertFalse(animation.begin("other", kind))
            animation.advance(1.25f, parked)
            assertEquals(.5f, animation.frame!!.approach, .001f)
            animation.advance(1.25f, parked)
            assertEquals(ServiceVisitAnimation.Phase.WORK, animation.frame!!.phase)
            assertEquals(1f, animation.frame!!.approach, 0f)
            animation.advance(8f, parked)
            assertEquals(ServiceVisitAnimation.Phase.LEAVE, animation.frame!!.phase)
            animation.advance(1.25f, parked)
            assertEquals(.5f, animation.frame!!.approach, .001f)
            animation.advance(1.25f, parked)
            assertNull(animation.frame)
            animation.advance(60f, parked)
            assertNull(animation.frame)
        }
    }

    @Test fun `departing restrictions and missing signals cancel immediately`() {
        for (motion in listOf(parked.copy(speedMps = .1f), parked.copy(speedMps = -.2f),
            parked.copy(speedMps = null), parked.copy(speedMps = Float.NaN),
            parked.copy(parkGear = false), parked.copy(parkGear = null),
            parked.copy(restricted = true), parked.copy(animationAllowed = false))) {
            val animation = ServiceVisitAnimation()
            animation.begin("visit", PlaceKind.FUEL)
            animation.advance(0f, motion)
            assertNull(animation.frame)
        }
    }

    @Test fun `only fresh current visits for the selected driver and vehicle qualify`() {
        fun eligible(value: PlaceVisit) = ServiceVisitAnimation.eligible(value, "car", "나", now, true)
        assertTrue(eligible(visit))
        assertTrue(eligible(visit.copy(state = VisitState.CONFIRMED)))
        for (invalid in listOf(visit.copy(state = VisitState.WATCHING), visit.copy(state = VisitState.EXCLUDED),
            visit.copy(exited = true), visit.copy(lastMatchedParked = false),
            visit.copy(vehicleId = "other"), visit.copy(user = "가족"),
            visit.copy(lastAt = now.minusSeconds(31)), visit.copy(lastAt = now.plusSeconds(1)))) {
            assertFalse(eligible(invalid))
        }
        assertFalse(ServiceVisitAnimation.eligible(visit.copy(place = place.copy(kind = PlaceKind.FUEL)), "car", "나", now, false))
    }

    @Test fun `actual tracker dwell qualifies without confirming or creating an entry`() {
        val store = MemoryStore().apply { replacePlaces(listOf(place)) }
        val tracker = PlaceVisitTracker(store)
        (0L..60L step 10).forEach { seconds ->
            val at = now.minusSeconds(60).plusSeconds(seconds)
            tracker.sample("car", "나", PlaceFix(37.5, 127.0, at), at, true, null)
            val tracked = store.visits("car").single()
            assertEquals(seconds == 60L, ServiceVisitAnimation.eligible(tracked, "car", "나", at, true))
        }
        assertEquals(VisitState.READY, store.visits("car").single().state)
        assertTrue(store.entries("car").isEmpty())
    }
}
