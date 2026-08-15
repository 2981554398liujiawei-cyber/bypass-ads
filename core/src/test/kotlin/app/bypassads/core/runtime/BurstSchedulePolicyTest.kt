package app.bypassads.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BurstSchedulePolicyTest {
    @Test
    fun `one hundred content events coalesce into one burst`() {
        val policy = BurstSchedulePolicy()
        repeat(100) { index ->
            assertIs<EventPlan.DebounceContent>(policy.onEvent(true, ScanTrigger.CONTENT_CHANGED, "com.demo", index.toLong()))
        }
        val plan = assertIs<EventPlan.StartBurst>(policy.onContentDebounceElapsed("com.demo", 349L))
        assertEquals(ScanTrigger.CONTENT_CHANGED, plan.trigger)
        assertIs<EventPlan.Ignore>(policy.onContentDebounceElapsed("com.demo", 350L))
    }

    @Test
    fun `same package content events within debounce replace pending callback`() {
        val policy = BurstSchedulePolicy()
        assertEquals(false, assertIs<EventPlan.DebounceContent>(policy.onEvent(true, ScanTrigger.CONTENT_CHANGED, "com.demo", 0L)).cancelExistingContent)
        assertEquals(true, assertIs<EventPlan.DebounceContent>(policy.onEvent(true, ScanTrigger.CONTENT_CHANGED, "com.demo", 100L)).cancelExistingContent)
        assertIs<EventPlan.Ignore>(policy.onContentDebounceElapsed("com.demo", 250L))
        assertIs<EventPlan.StartBurst>(policy.onContentDebounceElapsed("com.demo", 350L))
    }

    @Test
    fun `window event starts immediately and cancels pending content`() {
        val policy = BurstSchedulePolicy()
        policy.onEvent(true, ScanTrigger.CONTENT_CHANGED, "com.demo", 0L)
        val plan = assertIs<EventPlan.StartBurst>(policy.onEvent(true, ScanTrigger.WINDOWS_CHANGED, "com.demo", 20L))
        assertEquals(true, plan.cancelPendingContent)
        assertEquals(ScanTrigger.WINDOWS_CHANGED, plan.trigger)
    }

    @Test
    fun `each new burst explicitly replaces pending scan work`() {
        val policy = BurstSchedulePolicy()
        assertEquals(true, assertIs<EventPlan.StartBurst>(policy.onEvent(true, ScanTrigger.WINDOW_STATE_CHANGED, "com.one", 0L)).cancelActiveBurst)
        assertEquals(true, assertIs<EventPlan.StartBurst>(policy.onEvent(true, ScanTrigger.PACKAGE_CHANGED, "com.two", 10L)).cancelActiveBurst)
    }

    @Test
    fun `off mode schedules no scan`() {
        val policy = BurstSchedulePolicy()
        assertIs<EventPlan.Ignore>(policy.onEvent(false, ScanTrigger.CONTENT_CHANGED, "com.demo", 0L))
        assertIs<EventPlan.Ignore>(policy.onEvent(false, ScanTrigger.WINDOWS_CHANGED, "com.demo", 0L))
    }
}
