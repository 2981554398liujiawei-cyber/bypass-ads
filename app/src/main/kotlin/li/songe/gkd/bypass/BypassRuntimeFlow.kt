package li.songe.gkd.bypass

/**
 * Runtime V2 sequencing contract for one matched bypass rule.
 *
 * The production engine ([li.songe.gkd.a11y.A11yRuleEngine]) delegates its
 * per-rule decision here so the ordering rules are enforced by the same code
 * the runtime runs, and are integration-testable without an Android device:
 *
 *   match
 *   -> policy / context gate (engine, before this flow)
 *   -> action delay: WAIT only, never consumes budget
 *   -> status / outdate check
 *   -> immediately before performAction: reserveAttempt(sessionId)
 *   -> performAction
 *   -> verifier (separate)
 *
 * Nothing that merely schedules a delay, runs a query, or misses a selector
 * may consume an attempt. Only a real performAction consumes budget.
 */
object BypassRuntimeFlow {

    /** What the engine must do with the matched rule. */
    enum class Decision {
        /** Rule must wait for its action delay; no budget is consumed. */
        WAIT_FOR_DELAY,

        /** Rule is not ready (status / out-of-date); stop scanning. */
        NOT_READY,

        /** Budget exhausted; the session must finalize as a failure. */
        BUDGET_EXHAUSTED,

        /** Proceed: reserve one attempt and perform the action. */
        PROCEED,
    }

    /**
     * Effective per-session attempt cap. The hard ceiling is 3; the mode
     * policy and the rule-level cap (actionMaximum metadata) each apply:
     *
     *   minOf(mode.policy.maxExitAttempts, rulePolicy.maxAttempts, 3)
     */
    fun effectiveMaxAttempts(
        mode: BypassAdStrategyMode,
        rulePolicy: BypassRulePolicy,
    ): Int = minOf(
        mode.policy.maxExitAttempts,
        rulePolicy.maxAttempts,
        3,
    )

    /**
     * Decide the next step for one matched bypass rule. [budgetUsed] and
     * [maxAttempts] are the live values at decision time; the caller performs
     * the reservation only on [Decision.PROCEED].
     */
    fun decide(
        ruleHasPendingActionDelay: Boolean,
        ruleStatusOk: Boolean,
        outOfDate: Boolean,
        budgetUsed: Int,
        maxAttempts: Int,
    ): Decision = when {
        ruleHasPendingActionDelay -> Decision.WAIT_FOR_DELAY
        !ruleStatusOk || outOfDate -> Decision.NOT_READY
        budgetUsed >= maxAttempts -> Decision.BUDGET_EXHAUSTED
        else -> Decision.PROCEED
    }
}
