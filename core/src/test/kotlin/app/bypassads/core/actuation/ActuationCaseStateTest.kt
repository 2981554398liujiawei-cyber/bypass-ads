package app.bypassads.core.actuation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per-case actuation lifecycle (M2.1 task 2): first attempt allowed, second
 * attempt blocked, completed/failed blocked, fresh case allowed — and the
 * one-attempt rule cannot be bypassed by reusing or faking state.
 */
class ActuationCaseStateTest {

    private fun state(caseId: String = "case-a", generation: Long = 1L) =
        ActuationCaseState(caseId, generation, createdAtElapsedMs = 0L)

    @Test
    fun `fresh case starts active with zero attempts`() {
        val s = state()

        assertEquals(ActuationCaseStatus.ACTIVE, s.status)
        assertEquals(0, s.attemptCount)
        assertTrue(s.canAttempt)
    }

    @Test
    fun `first authorized attempt moves to attempted with count one`() {
        val s = state()
        s.recordAuthorizedAttempt()

        assertEquals(1, s.attemptCount)
        assertEquals(ActuationCaseStatus.ATTEMPTED, s.status)
        assertFalse(s.canAttempt)
    }

    @Test
    fun `second attempt on the same case is rejected`() {
        val s = state()
        s.recordAuthorizedAttempt()

        assertFalse(s.canAttempt)
        assertFailsWith<IllegalArgumentException> { s.recordAuthorizedAttempt() }
    }

    @Test
    fun `success outcome completes the case`() {
        val s = state()
        s.recordAuthorizedAttempt()
        s.finish(ActuationOutcome.SUCCESS)

        assertEquals(ActuationCaseStatus.COMPLETED, s.status)
        assertFalse(s.canAttempt)
    }

    @Test
    fun `no-effect outcome fails the case`() {
        val s = state()
        s.recordAuthorizedAttempt()
        s.finish(ActuationOutcome.NO_EFFECT)

        assertEquals(ActuationCaseStatus.FAILED, s.status)
        assertFalse(s.canAttempt)
    }

    @Test
    fun `uncertain outcome fails the case`() {
        val s = state()
        s.recordAuthorizedAttempt()
        s.finish(ActuationOutcome.UNCERTAIN)

        assertEquals(ActuationCaseStatus.FAILED, s.status)
        assertFalse(s.canAttempt)
    }

    @Test
    fun `completed case rejects further attempts`() {
        val s = state()
        s.recordAuthorizedAttempt()
        s.finish(ActuationOutcome.SUCCESS)

        assertFailsWith<IllegalArgumentException> { s.recordAuthorizedAttempt() }
    }

    @Test
    fun `failed case rejects further attempts`() {
        val s = state()
        s.recordAuthorizedAttempt()
        s.finish(ActuationOutcome.NO_EFFECT)

        assertFailsWith<IllegalArgumentException> { s.recordAuthorizedAttempt() }
    }

    @Test
    fun `a fresh case is allowed independently`() {
        val a = state("case-a")
        val b = state("case-b")

        a.recordAuthorizedAttempt()
        a.finish(ActuationOutcome.SUCCESS)

        assertFalse(a.canAttempt)
        assertTrue(b.canAttempt)
        b.recordAuthorizedAttempt()
        assertEquals(1, b.attemptCount)
    }

    @Test
    fun `new case generation cannot reset the attempted case`() {
        val a = state("case-a", generation = 1L)
        a.recordAuthorizedAttempt()
        // A "new generation" must be a new state instance; the old one stays sealed.
        val b = state("case-a", generation = 2L)

        assertEquals(1, a.attemptCount)
        assertFalse(a.canAttempt)
        assertTrue(b.canAttempt)
    }
}
