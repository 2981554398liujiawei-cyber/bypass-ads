package app.bypassads.core.decision

import app.bypassads.core.model.DecisionType
import app.bypassads.core.model.SkipCandidate
import app.bypassads.core.model.SkipDecision
import app.bypassads.core.model.WeightedReason
import kotlin.math.hypot

class DecisionEngine(
    private val clickThreshold: Int = 80,
) {
    fun decide(candidates: List<SkipCandidate>): SkipDecision {
        val best = candidates.maxByOrNull { it.score }
            ?: return SkipDecision(DecisionType.NO_CANDIDATE, null, clickThreshold)

        if (hasConflictingTrustedCandidate(best, candidates)) {
            val conflictRisk = WeightedReason("competing_candidate", -45)
            val rejected = best.copy(
                score = (best.score + conflictRisk.points).coerceIn(-100, 120),
                risks = best.risks + conflictRisk,
            )
            return SkipDecision(DecisionType.TOO_RISKY, rejected, clickThreshold, "conflicting_candidates")
        }

        val severeRisk = best.risks.any { it.points <= SEVERE_RISK_POINTS }
        return if (best.score >= clickThreshold && !severeRisk) {
            SkipDecision(DecisionType.WOULD_CLICK, best, clickThreshold)
        } else {
            val reason = if (severeRisk) "severe_risk" else "insufficient_evidence"
            SkipDecision(DecisionType.TOO_RISKY, best, clickThreshold, reason)
        }
    }

    private fun hasConflictingTrustedCandidate(
        best: SkipCandidate,
        candidates: List<SkipCandidate>,
    ): Boolean = candidates.asSequence()
        .filter { it !== best && it.score >= clickThreshold }
        .any { other -> distanceRatio(best, other) >= CONFLICT_DISTANCE_RATIO }

    private fun distanceRatio(first: SkipCandidate, second: SkipCandidate): Double {
        val width = first.features.screenWidth.coerceAtLeast(1).toDouble()
        val height = first.features.screenHeight.coerceAtLeast(1).toDouble()
        val dx = (first.bounds.centerX - second.bounds.centerX) / width
        val dy = (first.bounds.centerY - second.bounds.centerY) / height
        return hypot(dx, dy)
    }

    private companion object {
        const val SEVERE_RISK_POINTS = -50
        const val CONFLICT_DISTANCE_RATIO = 0.12
    }
}
