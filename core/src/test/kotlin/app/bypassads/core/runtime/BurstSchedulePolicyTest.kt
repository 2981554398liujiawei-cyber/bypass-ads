package app.bypassads.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BurstSchedulePolicyTest {
    @Test
    fun `continuous content events reach the maximum wait deadline`() {
        val policy = BurstSchedulePolicy()
        repeat(11) { index ->
            assertIs<EventPlan.DebounceContent>(policy.onEvent(true, ScanTrigger.CONTENT_CHANGED, "com.demo", index * 100L))
        }

        val plan = assertIs<EventPlan.StartBurst>(policy.onContentDebounceElapsed("com.demo", 1_000L))
        assertEquals(ScanTrigger.CONTENT_CHANGED, plan.trigger)
        assertIs<EventPlan.Ignore>(policy.onContentDebounceElapsed("com.demo", 1_100L))
    }

    @Test
    fun `same package events coalesce while a burst is active`() {
        val policy = BurstSchedulePolicy()
        assertIs<EventPlan.StartBurst>(policy.onEvent(true, ScanTrigger.PACKAGE_CHANGED, "com.demo", 0L))
        policy.markBurstStarted("com.demo")

        repeat(100) {
            assertIs<EventPlan.CoalesceActive>(policy.onEvent(true, ScanTrigger.WINDOWS_CHANGED, "com.demo", it.toLong()))
        }
        assertIs<EventPlan.CoalesceActive>(policy.onEvent(true, ScanTrigger.CONTENT_CHANGED, "com.demo", 101L))
    }

    @Test
    fun `a package change supersedes the active burst`() {
        val policy = BurstSchedulePolicy()
        assertIs<EventPlan.StartBurst>(policy.onEvent(true, ScanTrigger.PACKAGE_CHANGED, "com.one", 0L))
        policy.markBurstStarted("com.one")

        val next = assertIs<EventPlan.StartBurst>(policy.onEvent(true, ScanTrigger.PACKAGE_CHANGED, "com.two", 10L))
        assertEquals(true, next.supersedesActiveBurst)
        assertEquals("com.two", next.packageName)
    }

    @Test
    fun `completed burst permits a fresh same package event`() {
        val policy = BurstSchedulePolicy()
        assertIs<EventPlan.StartBurst>(policy.onEvent(true, ScanTrigger.WINDOW_STATE_CHANGED, "com.demo", 0L))
        policy.markBurstStarted("com.demo")
        policy.markBurstFinished()

        assertIs<EventPlan.StartBurst>(policy.onEvent(true, ScanTrigger.WINDOWS_CHANGED, "com.demo", 2_000L))
    }

    @Test
    fun `off mode schedules no scan and clears active state`() {
        val policy = BurstSchedulePolicy()
        assertIs<EventPlan.StartBurst>(policy.onEvent(true, ScanTrigger.PACKAGE_CHANGED, "com.demo", 0L))
        policy.markBurstStarted("com.demo")
        policy.disable()

        assertIs<EventPlan.Ignore>(policy.onEvent(false, ScanTrigger.CONTENT_CHANGED, "com.demo", 1L))
        assertIs<EventPlan.Ignore>(policy.onContentDebounceElapsed("com.demo", 250L))
    }
}
