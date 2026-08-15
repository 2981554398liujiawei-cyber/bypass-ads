package app.bypassads.core.detection

import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.SkipCandidate
import app.bypassads.core.model.WeightedReason

class CandidateScorer {
    fun score(features: CandidateFeatures): SkipCandidate {
        val evidence = mutableListOf<WeightedReason>()
        val risks = mutableListOf<WeightedReason>()
        val normalized = features.label.lowercase()
        val resourceId = features.resourceId?.lowercase().orEmpty()

        when {
            resourceId.contains("skip") -> evidence += WeightedReason("resource_id_skip", 36)
            resourceId.contains("close") -> evidence += WeightedReason("resource_id_close", 28)
        }

        when {
            normalized == "跳过" || normalized == "skip" -> evidence += WeightedReason("label_skip", 24)
            normalized == "关闭" || normalized == "close" -> evidence += WeightedReason("label_close", 14)
            normalized == "×" -> risks += WeightedReason("ambiguous_cross", -28)
        }

        val x = features.bounds.centerX.toDouble() / features.screenWidth.coerceAtLeast(1)
        val y = features.bounds.centerY.toDouble() / features.screenHeight.coerceAtLeast(1)
        if (x >= 0.70 && y <= 0.25) evidence += WeightedReason("top_right", 18)
        else if (x <= 0.30 && y <= 0.25) evidence += WeightedReason("top_left", 8)
        else if (x in 0.30..0.70 && y in 0.25..0.75) risks += WeightedReason("central_position", -22)

        val ratio = features.bounds.areaRatio(features.screenWidth, features.screenHeight)
        when {
            ratio <= 0.02 -> evidence += WeightedReason("small_target", 16)
            ratio <= 0.06 -> evidence += WeightedReason("compact_target", 10)
            ratio >= 0.20 -> risks += WeightedReason("large_target", -55)
        }

        if (features.clickable) evidence += WeightedReason("node_clickable", 12)
        if (features.stabilityHits >= 2) evidence += WeightedReason("stable_across_frames", 10)
        if (features.countdownStepObserved) evidence += WeightedReason("countdown_progression", 18)
        if (features.ctaSiblingDetected) risks += WeightedReason("cta_sibling", -35)

        features.nearestClickableAncestorBounds?.let { parentBounds ->
            val parentRatio = parentBounds.areaRatio(features.screenWidth, features.screenHeight)
            if (parentRatio >= 0.20) {
                risks += WeightedReason(
                    "large_clickable_ancestor",
                    if (features.clickable) -35 else -60,
                )
            }
        }

        if (!features.enabled || !features.visibleToUser) risks += WeightedReason("not_actionable", -100)

        val total = (evidence.sumOf { it.points } + risks.sumOf { it.points }).coerceIn(-100, 120)
        return SkipCandidate(features = features, score = total, evidence = evidence, risks = risks)
    }
}
