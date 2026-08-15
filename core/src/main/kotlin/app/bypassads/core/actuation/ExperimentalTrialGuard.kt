package app.bypassads.core.actuation

/**
 * Experimental trial protection (M2.2 task 5): global kill switch, per-session
 * action budget and experimental allowlist — enforced **before** any proposal
 * reaches the gate.
 *
 * - `enabled == false` (kill switch OFF) blocks immediately, without touching
 *   Shadow behaviour or the current case.
 * - The action budget (`maxActionsPerSession`, default 20) auto-suspends the
 *   trial once exhausted: further evaluations return
 *   [TrialGuardDecision.ACTION_BUDGET_EXHAUSTED].
 * - Only allowlisted packages may ever produce an action; everything else is
 *   Shadow-observed only.
 *
 * This is a pure-logic layer; the app wires it to persisted settings and the
 * service pipeline in M2.2.
 */
class ExperimentalTrialGuard(
    private val allowlist: Set<String>,
    private val maxActionsPerSession: Int = DEFAULT_MAX_ACTIONS_PER_SESSION,
) {
    /** Kill switch: OFF immediately blocks every action. */
    var enabled: Boolean = false

    var actionBudgetRemaining: Int = maxActionsPerSession
        private set

    fun evaluate(packageName: String?): TrialGuardDecision {
        if (!enabled) return TrialGuardDecision.KILL_SWITCH_OFF
        if (packageName == null || packageName !in allowlist) {
            return TrialGuardDecision.PACKAGE_NOT_ALLOWLISTED
        }
        if (actionBudgetRemaining <= 0) return TrialGuardDecision.ACTION_BUDGET_EXHAUSTED
        return TrialGuardDecision.ALLOW
    }

    /** Must be called only after an action is actually authorized and executed. */
    fun recordAction() {
        require(actionBudgetRemaining > 0) { "trial action budget exhausted" }
        actionBudgetRemaining--
    }

    companion object {
        const val DEFAULT_MAX_ACTIONS_PER_SESSION: Int = 20
    }
}

enum class TrialGuardDecision {
    /** Enabled, allowlisted, budget remaining — may proceed to the gate. */
    ALLOW,

    /** Kill switch OFF (or never enabled) — blocked immediately. */
    KILL_SWITCH_OFF,

    /** Package is not on the experimental allowlist — Shadow only. */
    PACKAGE_NOT_ALLOWLISTED,

    /** Per-session action budget exhausted — trial auto-paused. */
    ACTION_BUDGET_EXHAUSTED,
}
