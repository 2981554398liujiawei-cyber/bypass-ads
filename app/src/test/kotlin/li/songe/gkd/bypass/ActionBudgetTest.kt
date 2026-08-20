package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-session action budget: reservations happen before actions, failed
 * actions still consume budget, and the session id (never package name) is
 * the accounting key.
 */
class ActionBudgetTest {

    @Test
    fun budget_limits_attempts_per_session() {
        BypassActionBudget.clear()
        val session = "s-1"
        assertTrue(BypassActionBudget.reserveAttempt(session, 2))
        assertTrue(BypassActionBudget.reserveAttempt(session, 2))
        assertFalse("third attempt over budget", BypassActionBudget.reserveAttempt(session, 2))
        assertEquals(2, BypassActionBudget.attemptsUsed(session))
    }

    @Test
    fun conservative_budget_is_one() {
        BypassActionBudget.clear()
        val session = "s-conservative"
        assertTrue(BypassActionBudget.reserveAttempt(session, BypassAdStrategyMode.CONSERVATIVE.policy.maxExitAttempts))
        assertFalse(BypassActionBudget.reserveAttempt(session, BypassAdStrategyMode.CONSERVATIVE.policy.maxExitAttempts))
    }

    @Test
    fun budgets_are_keyed_by_session_id_not_package() {
        BypassActionBudget.clear()
        // Two sessions in the same package (WeChat mini-programs) are separate.
        assertTrue(BypassActionBudget.reserveAttempt("wx-mini-a", 1))
        assertFalse(BypassActionBudget.reserveAttempt("wx-mini-a", 1))
        assertTrue("different session gets its own budget", BypassActionBudget.reserveAttempt("wx-mini-b", 1))
    }

    @Test
    fun reset_frees_budget() {
        BypassActionBudget.clear()
        val session = "s-reset"
        assertTrue(BypassActionBudget.reserveAttempt(session, 1))
        BypassActionBudget.reset(session)
        assertTrue("reset allows a new attempt", BypassActionBudget.reserveAttempt(session, 1))
        BypassActionBudget.clear()
    }
}
