package app.bypassads.core.detection

import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.CtaLabels
import app.bypassads.core.model.LabelSanitizer
import app.bypassads.core.model.SkipCandidate
import app.bypassads.core.model.UiNodeSnapshot
import app.bypassads.core.model.UiSnapshot

class CandidateDetector(
    private val scorer: CandidateScorer = CandidateScorer(),
) {
    fun extract(snapshot: UiSnapshot): List<CandidateFeatures> {
        val packageName = snapshot.packageName ?: return emptyList()
        return snapshot.nodes
            .asSequence()
            .filter { it.visibleToUser && it.enabled }
            .filter { it.packageName == packageName }
            .mapNotNull { node -> toFeatures(snapshot, node) }
            .toList()
    }

    fun score(features: List<CandidateFeatures>): List<SkipCandidate> = features
        .map(scorer::score)
        .sortedByDescending { it.score }

    fun detect(snapshot: UiSnapshot): List<SkipCandidate> = score(extract(snapshot))

    private fun toFeatures(snapshot: UiSnapshot, node: UiNodeSnapshot): CandidateFeatures? {
        val rawLabel = bestLabel(node.text, node.contentDescription) ?: return null
        return CandidateFeatures(
            nodeIndex = node.index,
            label = sanitizeLabel(rawLabel),
            resourceId = node.resourceId,
            bounds = node.bounds,
            clickable = node.clickable,
            enabled = node.enabled,
            visibleToUser = node.visibleToUser,
            nearestClickableAncestorBounds = node.nearestClickableAncestorBounds,
            screenWidth = snapshot.screenWidth,
            screenHeight = snapshot.screenHeight,
            countdownValue = extractCountdown(rawLabel),
            ctaSiblingDetected = hasCtaSibling(snapshot, node),
        )
    }

    private fun hasCtaSibling(snapshot: UiSnapshot, node: UiNodeSnapshot): Boolean {
        val parent = node.parentIndex ?: return false
        return snapshot.nodes.asSequence()
            .filter { it.index != node.index && it.parentIndex == parent && it.visibleToUser }
            .mapNotNull { sequenceOf(it.text, it.contentDescription).filterNotNull().firstOrNull() }
            .any(CtaLabels::matches)
    }

    private fun bestLabel(text: String?, description: String?): String? {
        val values = listOfNotNull(text, description).map { it.trim() }
        val direct = values.firstOrNull { labelRegex.containsMatchIn(it) || crossOnly.matches(it) }
        return direct?.take(40)
    }

    private fun extractCountdown(value: String): Int? = Regex("(?<!\\d)(\\d{1,2})(?!\\d)")
        .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun sanitizeLabel(value: String): String = LabelSanitizer.sanitizeLabel(value)

    private companion object {
        val labelRegex = Regex("(?i)(跳过|关闭|跳過|關閉|skip|close)")
        val crossOnly = Regex("^[\\s×✕✖xX]{1,3}$")
    }
}
