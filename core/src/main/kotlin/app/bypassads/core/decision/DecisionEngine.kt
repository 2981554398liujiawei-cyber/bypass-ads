package app.bypassads.core.decision

import app.bypassads.core.model.DecisionType
import app.bypassads.core.model.SkipCandidate
import app.bypassads.core.model.SkipDecision

class DecisionEngine(
    private val clickThreshold: Int = 80,
) {
    fun decide(candidates: List<SkipCandidate>): SkipDecision {
        val best = candidates.maxByOrNull { it.score }
            ?: return SkipDecision(DecisionType.NO_CANDIDATE, null, clickThreshold)

        return if (best.score >= clickThreshold && best.risks.none { it.points <= -50 }) {
            SkipDecision(DecisionType.WOULD_CLICK, best, clickThreshold)
        } else {
            SkipDecision(DecisionType.TOO_RISKY, best, clickThreshold)
        }
    }
}
