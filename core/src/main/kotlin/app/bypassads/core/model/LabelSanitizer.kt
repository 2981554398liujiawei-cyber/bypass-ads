package app.bypassads.core.model

/**
 * Shared label normalization used by detection and actuation re-resolution.
 * Kept byte-identical to the original CandidateDetector rules so Shadow
 * behaviour does not change.
 */
object LabelSanitizer {
    private val crossOnly = Regex("^[\\s×✕✖xX]{1,3}$")

    fun sanitizeLabel(value: String): String = when {
        value.contains("跳过") || value.contains("跳過") -> "跳过"
        value.contains("关闭") || value.contains("關閉") -> "关闭"
        value.lowercase().contains("skip") -> "skip"
        value.lowercase().contains("close") -> "close"
        crossOnly.matches(value.trim()) -> "×"
        else -> "matched"
    }
}

/**
 * CTA text markers reused by detection (sibling scan) and fresh re-resolution.
 */
object CtaLabels {
    val REGEX = Regex(
        "(?i)(立即|领取|領取|下载|下載|安装|安裝|打开|打開|查看详情|查看詳情|购买|購買|去看看|了解更多|install|download|open|learn more|shop now|buy now)",
    )

    fun matches(text: String): Boolean = REGEX.containsMatchIn(text)
}
