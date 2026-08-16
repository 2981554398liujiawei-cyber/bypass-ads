package app.bypassads.core.actuation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActuationDispatchTrackerTest {

    @Test
    fun beginAttempt_clearsPreviousDispatchState() {
        val tracker = ActuationDispatchTracker()
        tracker.recordDispatch(false)
        assertEquals(false, tracker.dispatchReported)

        // Next attempt starts fresh: a stale false must never be inherited.
        tracker.beginAttempt()
        assertEquals(null, tracker.dispatchReported)
        assertFalse(tracker.verificationWarranted)
    }

    @Test
    fun noDispatch_recorded_verificationNotWarranted() {
        // Mirrors "0/>1 live matches or performAction never returned": begin
        // happened but no click was dispatched — no passive verification.
        val tracker = ActuationDispatchTracker()
        tracker.beginAttempt()
        assertFalse(tracker.verificationWarranted)
        assertEquals(null, tracker.dispatchReported)
    }

    @Test
    fun performActionFalse_stillCountsAsDispatched_verificationWarranted() {
        // HyperOS: performAction=false can still take effect, so false must
        // still enter passive verification.
        val tracker = ActuationDispatchTracker()
        tracker.beginAttempt()
        tracker.recordDispatch(false)
        assertEquals(false, tracker.dispatchReported)
        assertTrue(tracker.verificationWarranted)
    }

    @Test
    fun performActionTrue_countsAsDispatched_verificationWarranted() {
        val tracker = ActuationDispatchTracker()
        tracker.beginAttempt()
        tracker.recordDispatch(true)
        assertEquals(true, tracker.dispatchReported)
        assertTrue(tracker.verificationWarranted)
    }
}
