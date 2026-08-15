package app.bypassads.core.actuation

import app.bypassads.core.model.SkipCandidate
import app.bypassads.core.model.SkipDecision

/**
 * A Shadow [SkipDecision] promoted to an actuation request. Producing an
 * [ActionProposal] does **not** authorize a click (ADR-0002 §1): the proposal
 * must still pass the fail-closed [ActuationGate] after fresh re-resolution.
 */
data class ActionProposal(
    val candidate: SkipCandidate,
    val packageName: String?,
    val windowCountAtProposal: Int,
    val proposedAtElapsedMs: Long,
    val countdownValue: Int?,
    val fingerprint: TargetFingerprint,
) {
    val score: Int get() = candidate.score

    companion object {
        /**
         * @return null when the decision is not [app.bypassads.core.model.DecisionType.WOULD_CLICK]
         * or carries no candidate (nothing to propose).
         */
        fun fromDecision(
            decision: SkipDecision,
            packageName: String?,
            windowCount: Int,
            elapsedMs: Long,
            countdownValue: Int? = null,
        ): ActionProposal? {
            val candidate = decision.candidate ?: return null
            if (decision.type != app.bypassads.core.model.DecisionType.WOULD_CLICK) return null
            return ActionProposal(
                candidate = candidate,
                packageName = packageName,
                windowCountAtProposal = windowCount,
                proposedAtElapsedMs = elapsedMs,
                countdownValue = countdownValue,
                fingerprint = buildTargetFingerprint(candidate, packageName),
            )
        }
    }
}
