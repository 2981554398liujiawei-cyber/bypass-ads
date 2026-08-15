package app.bypassads.core.detection

import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.SkipCandidate
import app.bypassads.core.model.UiNodeSnapshot
import app.bypassads.core.model.UiSnapshot

class CandidateDetector(
    private val scorer: CandidateScorer = CandidateScorer(),
) {
    fun extract(snapshot: UiSnapshot): List<CandidateFeatures> = snapshot.nodes
        .asSequence()
        .filter { it.visibleToUser && it.enabled }
        .filter { snapshot.packageName == null || it.packageName == null || it.packageName == snapshot.packageName }
        .mapNotNull { node -> toFeatures(snapshot, node) }
        .toList()

    fun score(features: List<CandidateFeatures>): List<SkipCandidate> = features
        .map(scorer::score)
        .sortedByDescending { it.score }

    fun detect(snapshot: UiSnapshot): List<SkipCandidate> = score(extract(snapshot))

    private fun toFeatures(snapshot: UiSnapshot, node: UiNodeSnapshot): CandidateFeatures? {
        val rawLabel = bestLabel(node.text, node.contentDescription, node.resourceId) ?: return null
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
            .any { ctaRegex.containsMatchIn(it) }
    }

    private fun bestLabel(text: String?, description: String?, resourceId: String?): String? {
        val values = listOfNotNull(text, description).map { it.trim() }
        val direct = values.firstOrNull { labelRegex.containsMatchIn(it) || crossOnly.matches(it) }
        if (direct != null) return direct.take(40)
        val id = resourceId?.lowercase().orEmpty()
        return if (id.contains("skip") || id.contains("close")) "<resource-id>" else null
    }

    private fun extractCountdown(value: String): Int? = Regex("(?<!\\d)(\\d{1,2})(?!\\d)")
        .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun sanitizeLabel(value: String): String = when {
        value == "<resource-id>" -> value
        value.contains("跳过") || value.contains("跳過") -> "跳过"
        value.contains("关闭") || value.contains("關閉") -> "关闭"
        value.lowercase().contains("skip") -> "skip"
        value.lowercase().contains("close") -> "close"
        crossOnly.matches(value.trim()) -> "×"
        else -> "matched"
    }

    private companion object {
        val labelRegex = Regex("(?i)(跳过|关闭|跳過|關閉|skip|close)")
        val crossOnly = Regex("^[\\s×✕✖xX]{1,3}$")
        val ctaRegex = Regex("(?i)(立即|领取|領取|下载|下載|安装|安裝|打开|打開|查看详情|查看詳情|购买|購買|去看看|了解更多|install|download|open|learn more|shop now|buy now)")
    }
}
