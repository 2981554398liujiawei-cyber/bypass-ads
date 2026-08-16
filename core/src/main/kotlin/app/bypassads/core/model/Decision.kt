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
     * True when this candidate was produced by a statically approved
     * Real-App Fast Path rule (package + label + region + stability), not by
     * the generic scorer. Such candidates are allowed to bypass the generic
     * clickable-node requirement further down the chain (M2.3 Real-App Pilot).
     */
    val fastPath: Boolean = false,
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
