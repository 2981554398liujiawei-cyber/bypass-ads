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
    /** Monotonic scan/case generation the proposal belongs to (see ShadowScanCoordinator.generation). */
    val caseGeneration: Long,
    /** Abstract identity of the window the proposal was made against (M2.1 binds real window identity). */
    val windowContextToken: Long,
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
            caseGeneration: Long,
            windowContextToken: Long,
            elapsedMs: Long,
            countdownValue: Int? = null,
        ): ActionProposal? {
            val candidate = decision.candidate ?: return null
            if (decision.type != app.bypassads.core.model.DecisionType.WOULD_CLICK) return null
            return ActionProposal(
                candidate = candidate,
                packageName = packageName,
                caseGeneration = caseGeneration,
                windowContextToken = windowContextToken,
                proposedAtElapsedMs = elapsedMs,
                countdownValue = countdownValue,
                fingerprint = buildTargetFingerprint(candidate, packageName),
            )
        }
    }
}
