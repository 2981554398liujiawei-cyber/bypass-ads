package app.bypassads.core.model

data class WeightedReason(
    val code: String,
    val points: Int,
)

data class CandidateFeatures(
    val nodeIndex: Int,
    val label: String,
    val resourceId: String?,
    val bounds: IntRect,
    val clickable: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val nearestClickableAncestorBounds: IntRect?,
    val screenWidth: Int,
    val screenHeight: Int,
    val stabilityHits: Int = 1,
    val countdownValue: Int? = null,
    val countdownStepObserved: Boolean = false,
    val positionDriftDetected: Boolean = false,
    val ctaSiblingDetected: Boolean = false,
    val identityAmbiguous: Boolean = false,
)

data class SkipCandidate(
    val features: CandidateFeatures,
    val score: Int,
    val evidence: List<WeightedReason>,
    val risks: List<WeightedReason>,
    /**
     * When non-null, this candidate was produced by a statically approved
     * Real-App Fast Path rule (M2.3): the value is the exact rule id the fresh
     * revalidation must re-hit, and the gate relaxes only the node-level
     * `clickable` requirement for such targets (the rule already approved
     * package + label + splash scope + region + stability).
     */
    val fastPathRuleId: String? = null,
) {
    val nodeIndex: Int get() = features.nodeIndex
    val label: String get() = features.label
    val resourceId: String? get() = features.resourceId
    val bounds: IntRect get() = features.bounds
}

enum class DecisionType {
    WOULD_CLICK,
    TOO_RISKY,
    NO_CANDIDATE,
}

data class SkipDecision(
    val type: DecisionType,
    val candidate: SkipCandidate?,
    val threshold: Int,
    val rejectionReason: String? = null,
)
