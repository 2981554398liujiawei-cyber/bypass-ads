package app.bypassads.core.detection

import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.LabelSanitizer
import app.bypassads.core.model.SkipCandidate
import app.bypassads.core.model.UiSnapshot
import app.bypassads.core.model.WeightedReason

/**
 * Real-App Fast Path (M2.3 Real-App Pilot).
 *
 * The generic scorer is intentionally conservative ("miss rather than
 * mis-click"): on real ad splash pages a skip button is frequently exposed as
 * a non-clickable node (the click is handled by a parent container), so the
 * generic path can never reach WOULD_CLICK for it — observed on
 * com.sina.weibo ("跳过", clickable=false, score 68 < 80).
 *
 * A Fast Path rule is a *statically approved, per-package identity*:
 * package + sanitized label + top-right region + multi-frame stability.
 * When a rule matches, this class produces a high-confidence candidate
 * (score 90, `fastPath = true`) that rides the normal decision/revalidation/
 * gate pipeline. It never bypasses the pipeline — the fail-closed
 * [app.bypassads.core.actuation.ActuationGate] still re-checks
 * generation/window/timestamp/uniqueness/geometry/region, and the only
 * relaxation is the node-level `clickable` flag (the rule already statically
 * approved package+label+region+stability; a non-clickable text node inside
 * the ad container is expected structure, not ambiguity).
 *
 * Rules are added only after human-reviewed real-app shadow evidence
 * (label + bounds region confirmed stable across cold starts).
 */
data class FastPathRule(
    val packageName: String,
    val label: String,
    /** Minimum `stabilityHits` (multi-frame stability) required before the rule applies. */
    val minStabilityHits: Int = 2,
) {
    init {
        require(minStabilityHits >= 1) { "minStabilityHits must be >= 1" }
    }
}

class RealAppFastPath(
    private val rules: List<FastPathRule>,
) {

    /**
     * @return Fast Path candidates for the snapshot's package, matched against
     * the already-enriched candidate features (same node indexes the generic
     * detector scored), or an empty list when no rule applies.
     */
    fun score(snapshot: UiSnapshot, features: List<CandidateFeatures>): List<SkipCandidate> {
        val pkg = snapshot.packageName ?: return emptyList()
        val rule = rules.firstOrNull { it.packageName == pkg } ?: return emptyList()
        val width = snapshot.screenWidth.coerceAtLeast(1)
        val height = snapshot.screenHeight.coerceAtLeast(1)
        val label = LabelSanitizer.sanitizeLabel(rule.label)
        return features.asSequence()
            .filter { it.label == label }
            .filter { it.stabilityHits >= rule.minStabilityHits }
            // Top-right region — the same zone ActuationGate requires for any
            // allowed click; kept here as a second, earlier safety layer.
            .filter {
                it.bounds.centerX.toDouble() / width >= TOP_RIGHT_MIN_X &&
                    it.bounds.centerY.toDouble() / height <= TOP_RIGHT_MAX_Y
            }
            // Hard disqualifiers: a rule must never fire on ambiguous targets.
            .filter { !it.identityAmbiguous && !it.ctaSiblingDetected && !it.positionDriftDetected }
            .map { feature ->
                SkipCandidate(
                    features = feature,
                    score = FAST_PATH_SCORE,
                    evidence = listOf(
                        WeightedReason("fast_path_rule", FAST_PATH_EVIDENCE),
                        WeightedReason("label_skip", 24),
                        WeightedReason("top_right", 18),
                        WeightedReason("small_target", 16),
                        WeightedReason("stable_across_frames", 10),
                    ),
                    risks = emptyList(),
                    fastPath = true,
                )
            }
            .toList()
    }

    companion object {
        /** Score for a Fast Path match — comfortably above the 80 click threshold. */
        const val FAST_PATH_SCORE = 90
        const val FAST_PATH_EVIDENCE = 40
        const val TOP_RIGHT_MIN_X = 0.70
        const val TOP_RIGHT_MAX_Y = 0.25
    }
}
