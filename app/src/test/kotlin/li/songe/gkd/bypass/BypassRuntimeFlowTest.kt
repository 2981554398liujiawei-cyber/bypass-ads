package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime V2 sequencing integration test. Drives the SAME decision code the
 * production engine runs ([BypassRuntimeFlow]) through the full lifecycle of
 * one matched bypass rule — match -> gate -> delay (no budget) -> status ->
 * reserve -> perform — plus the per-session budget, so this is a runtime
 * contract test, not just a unit test of the BypassActionBudget object.
 *
 * Hard acceptance (P0-1):
 *  - Conservative + delayed Skip: waiting consumes 0 attempts, the value
 *    becomes 1 immediately before performAction, final actionAttempts = 1.
 *  - Schedule-delay / selector-miss / re-query paths never consume an attempt.
 *  - effectiveMaxAttempts = minOf(mode.policy.maxExitAttempts,
 *    rulePolicy.maxAttempts, 3).
 */
class BypassRuntimeFlowTest {

    private fun rulePolicy(maxAttempts: Int = 3) = BypassRulePolicy(
        trust = BypassRuleTrust.BUNDLED_DEDICATED,
        minimumMode = BypassAdStrategyMode.CONSERVATIVE,
        requiresStrongAdContext = false,
        coordinate = false,
        maxAttempts = maxAttempts,
    )

    @Test
    fun effective_max_attempts_is_min_of_mode_rule_and_hard_cap() {
        // Mode cap + rule cap + hard ceiling of 3.
        assertEquals(1, BypassRuntimeFlow.effectiveMaxAttempts(BypassAdStrategyMode.CONSERVATIVE, rulePolicy(maxAttempts = 3)))
        assertEquals(2, BypassRuntimeFlow.effectiveMaxAttempts(BypassAdStrategyMode.AGGRESSIVE, rulePolicy(maxAttempts = 3)))
        assertEquals(3, BypassRuntimeFlow.effectiveMaxAttempts(BypassAdStrategyMode.CRAZY, rulePolicy(maxAttempts = 3)))
        // A rule-level cap (actionMaximum metadata) tightens the mode cap.
        assertEquals(1, BypassRuntimeFlow.effectiveMaxAttempts(BypassAdStrategyMode.CRAZY, rulePolicy(maxAttempts = 1)))
        assertEquals(2, BypassRuntimeFlow.effectiveMaxAttempts(BypassAdStrategyMode.CRAZY, rulePolicy(maxAttempts = 2)))
        // The hard ceiling is 3 no matter what the rule claims.
        assertEquals(3, BypassRuntimeFlow.effectiveMaxAttempts(BypassAdStrategyMode.CRAZY, rulePolicy(maxAttempts = 99)))
    }

    @Test
    fun conservative_delayed_skip_consumes_budget_only_at_perform() {
        BypassActionBudget.clear()
        val sessionId = "runtime-cons-delayed-skip"
        val mode = BypassAdStrategyMode.CONSERVATIVE
        val maxAttempts = BypassRuntimeFlow.effectiveMaxAttempts(mode, rulePolicy())

        // ---- First match: the rule has an action delay (e.g. WeChat Skip
        // with actionDelay=800ms). The engine must WAIT and NOT reserve. ----
        val first = BypassRuntimeFlow.decide(
            ruleHasPendingActionDelay = true, // rule.checkDelay() returned true
            ruleStatusOk = false,             // status becomes Status6 while waiting
            outOfDate = false,
            budgetUsed = BypassActionBudget.attemptsUsed(sessionId),
            maxAttempts = maxAttempts,
        )
        assertEquals(BypassRuntimeFlow.Decision.WAIT_FOR_DELAY, first)
        assertEquals("waiting must not consume an attempt", 0, BypassActionBudget.attemptsUsed(sessionId))

        // ---- While the delay runs, unrelated events keep re-matching: the
        // rule is still not ready, still nothing is consumed. ----
        val during = BypassRuntimeFlow.decide(
            ruleHasPendingActionDelay = false, // delay job already scheduled
            ruleStatusOk = false,              // still Status6
            outOfDate = false,
            budgetUsed = BypassActionBudget.attemptsUsed(sessionId),
            maxAttempts = maxAttempts,
        )
        assertEquals(BypassRuntimeFlow.Decision.NOT_READY, during)
        assertEquals(0, BypassActionBudget.attemptsUsed(sessionId))

        // ---- Delay elapsed: status is OK. Decide PROCEED, then reserve
        // immediately before performAction. ----
        val proceed = BypassRuntimeFlow.decide(
            ruleHasPendingActionDelay = false,
            ruleStatusOk = true,
            outOfDate = false,
            budgetUsed = BypassActionBudget.attemptsUsed(sessionId),
            maxAttempts = maxAttempts,
        )
        assertEquals(BypassRuntimeFlow.Decision.PROCEED, proceed)
        assertTrue("reserve happens right before the action", BypassActionBudget.reserveAttempt(sessionId, maxAttempts))
        assertEquals("one attempt consumed by the real action", 1, BypassActionBudget.attemptsUsed(sessionId))

        // The session records exactly one action attempt for this ad.
        assertEquals(1, BypassActionBudget.attemptsUsed(sessionId))
        BypassActionBudget.clear()
    }

    @Test
    fun selector_miss_and_requery_never_consume_attempts() {
        BypassActionBudget.clear()
        val sessionId = "runtime-query-miss"
        val maxAttempts = BypassRuntimeFlow.effectiveMaxAttempts(BypassAdStrategyMode.CONSERVATIVE, rulePolicy())

        // Selector miss: the rule matched the app but no node matched the
        // selector. The engine `continue`s BEFORE any sequencing decision —
        // this is exactly what "query/selector miss must not consume" means.
        assertEquals(0, BypassActionBudget.attemptsUsed(sessionId))

        // A re-query scheduled by OutcomeVerifier (ACTION_NO_EFFECT) does not
        // itself reserve; only a subsequent PROCEED does.
        val requery = BypassRuntimeFlow.decide(
            ruleHasPendingActionDelay = false,
            ruleStatusOk = true,
            outOfDate = false,
            budgetUsed = BypassActionBudget.attemptsUsed(sessionId),
            maxAttempts = maxAttempts,
        )
        assertEquals(BypassRuntimeFlow.Decision.PROCEED, requery)
        assertEquals("decide alone never consumes", 0, BypassActionBudget.attemptsUsed(sessionId))
        BypassActionBudget.clear()
    }

    @Test
    fun budget_exhausted_blocks_further_actions_in_same_session() {
        BypassActionBudget.clear()
        val sessionId = "runtime-budget-exhausted"
        val mode = BypassAdStrategyMode.CONSERVATIVE
        val maxAttempts = BypassRuntimeFlow.effectiveMaxAttempts(mode, rulePolicy())

        // First perform consumes the single conservative attempt.
        assertTrue(BypassActionBudget.reserveAttempt(sessionId, maxAttempts))
        assertEquals(1, BypassActionBudget.attemptsUsed(sessionId))

        // The ad is still there; the next match must NOT proceed.
        val next = BypassRuntimeFlow.decide(
            ruleHasPendingActionDelay = false,
            ruleStatusOk = true,
            outOfDate = false,
            budgetUsed = BypassActionBudget.attemptsUsed(sessionId),
            maxAttempts = maxAttempts,
        )
        assertEquals(BypassRuntimeFlow.Decision.BUDGET_EXHAUSTED, next)
        assertFalse(BypassActionBudget.reserveAttempt(sessionId, maxAttempts))
        assertEquals(1, BypassActionBudget.attemptsUsed(sessionId))
        BypassActionBudget.clear()
    }

    @Test
    fun delayed_rule_never_reserves_when_action_never_happens() {
        BypassActionBudget.clear()
        val sessionId = "runtime-delay-never-performed"
        val maxAttempts = BypassRuntimeFlow.effectiveMaxAttempts(BypassAdStrategyMode.AGGRESSIVE, rulePolicy())

        // The window closes (user leaves) while the rule is still waiting.
        assertEquals(
            BypassRuntimeFlow.Decision.WAIT_FOR_DELAY,
            BypassRuntimeFlow.decide(
                ruleHasPendingActionDelay = true,
                ruleStatusOk = false,
                outOfDate = false,
                budgetUsed = BypassActionBudget.attemptsUsed(sessionId),
                maxAttempts = maxAttempts,
            ),
        )
        assertEquals("an action that never runs must never consume", 0, BypassActionBudget.attemptsUsed(sessionId))
        BypassActionBudget.clear()
    }

    @Test
    fun out_of_date_match_stops_without_consuming() {
        BypassActionBudget.clear()
        val sessionId = "runtime-out-of-date"
        val maxAttempts = BypassRuntimeFlow.effectiveMaxAttempts(BypassAdStrategyMode.AGGRESSIVE, rulePolicy())

        // The rule became out of date (activity/app changed mid-match).
        assertEquals(
            BypassRuntimeFlow.Decision.NOT_READY,
            BypassRuntimeFlow.decide(
                ruleHasPendingActionDelay = false,
                ruleStatusOk = true,
                outOfDate = true,
                budgetUsed = BypassActionBudget.attemptsUsed(sessionId),
                maxAttempts = maxAttempts,
            ),
        )
        assertEquals(0, BypassActionBudget.attemptsUsed(sessionId))
        BypassActionBudget.clear()
    }
}
