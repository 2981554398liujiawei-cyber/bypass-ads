package app.bypassads.core.actuation

import app.bypassads.core.model.WeightedReason

/**
 * Gated actuation runner: the only production-shaped path from a proposal to
 * an executed node action (M2.1 task 2/4).
 *
 * Wires the hard invariants together so they cannot be skipped:
 * - `ActuationGate` is the single authorization entry;
 * - `ActuationCaseState` supplies case activity and attempt count, and
 *   records the authorized attempt (0 -> 1) right after Allow;
 * - `NodeActuator` receives only an Allow verdict (typed at the interface);
 * - the post-action outcome closes the case via
 *   [ActuationCaseState.finish] — NO_EFFECT/UNCERTAIN end the case as FAILED
 *   and are never retried.
 */
class ActuationSession(
    private val gate: ActuationGate = ActuationGate(),
    private val caseState: ActuationCaseState,
    private val actuator: NodeActuator,
) {
    fun run(
        proposal: ActionProposal,
        fresh: FreshTargetResolution,
        modeAllowsAction: Boolean,
        revalidatedScore: Int,
        revalidatedRisks: List<WeightedReason>,
    ): ActuationResult {
        val verdict = gate.evaluate(
            proposal = proposal,
            modeAllowsAction = modeAllowsAction,
            caseActive = caseState.status == ActuationCaseStatus.ACTIVE,
            attemptsAlreadyMade = caseState.attemptCount,
            fresh = fresh,
            revalidatedScore = revalidatedScore,
            revalidatedRisks = revalidatedRisks,
        )
        val allow = verdict as? ActuationVerdict.Allow
            ?: return ActuationResult.Blocked((verdict as ActuationVerdict.Block).reason)

        caseState.recordAuthorizedAttempt()
        val outcome = runCatching { actuator.actuate(allow, fresh) }
            .getOrElse { ActuationOutcome.UNCERTAIN }
        caseState.finish(outcome)
        return ActuationResult.Executed(outcome)
    }
}

sealed interface ActuationResult {
    data class Blocked(val reason: ActuationBlockReason) : ActuationResult
    data class Executed(val outcome: ActuationOutcome) : ActuationResult
}
