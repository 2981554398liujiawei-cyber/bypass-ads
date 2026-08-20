package li.songe.gkd.bypass

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Three-tier splash exit strategy.
 *
 * The mode decides which candidates, which fallbacks, which actions, and how
 * many exit attempts are allowed. AGGRESSIVE and CRAZY must differ in
 * candidate discovery: only CRAZY may run structural no-semantic close and
 * coordinate fallback, and only inside STRONG ad context.
 */
enum class BypassAdStrategyMode {
    CONSERVATIVE,
    AGGRESSIVE,
    CRAZY;

    val label: String
        get() = when (this) {
            CONSERVATIVE -> "保守"
            AGGRESSIVE -> "激进"
            CRAZY -> "彻底疯狂"
        }

    val description: String
        get() = when (this) {
            CONSERVATIVE -> "只运行成熟专用规则与明确的“跳过”语义控件。误触风险最低，可能漏过关闭/X 类广告。"
            AGGRESSIVE -> "确认广告上下文后，识别“跳过、关闭、Close、ad_close、×”等控件并允许有限重试。仍不操作无语义结构控件。"
            CRAZY -> "确认广告上下文后，进一步尝试结构型关闭控件与受限坐标规则。成功率优先，误触风险最高。"
        }

    val policy: BypassStrategyPolicy
        get() = when (this) {
            CONSERVATIVE -> BypassStrategyPolicy(
                allowGenericCloseText = false,
                allowCloseViewId = false,
                allowGlyphClose = false,
                allowStructuralNoSemanticClose = false,
                allowCoordinateFallback = false,
                maxExitAttempts = 1,
            )
            AGGRESSIVE -> BypassStrategyPolicy(
                allowGenericCloseText = true,
                allowCloseViewId = true,
                allowGlyphClose = true,
                allowStructuralNoSemanticClose = false,
                allowCoordinateFallback = false,
                maxExitAttempts = 2,
            )
            CRAZY -> BypassStrategyPolicy(
                allowGenericCloseText = true,
                allowCloseViewId = true,
                allowGlyphClose = true,
                allowStructuralNoSemanticClose = true,
                allowCoordinateFallback = true,
                maxExitAttempts = 3,
            )
        }

    companion object {
        fun from(value: Int): BypassAdStrategyMode = entries.getOrElse(value) { CONSERVATIVE }
    }
}

/** Runtime gating parameters for one [BypassAdStrategyMode]. */
data class BypassStrategyPolicy(
    val allowGenericCloseText: Boolean,
    val allowCloseViewId: Boolean,
    /** Explicit X / × / ✕ glyph with close semantics. */
    val allowGlyphClose: Boolean,
    /** Small text-less structural ImageView/View close candidates. */
    val allowStructuralNoSemanticClose: Boolean,
    val allowCoordinateFallback: Boolean,
    val maxExitAttempts: Int,
)

/** What kind of ad-exit control a matched node looks like. */
enum class BypassExitCandidateType {
    SKIP_TEXT,
    CLOSE_TEXT,
    CLOSE_DESC,
    CLOSE_VIEW_ID,
    /** Explicit X / × / ✕ glyph. */
    CLOSE_ICON,
    /** Small text-less structural ImageView/View (no semantics). */
    STRUCTURAL_CLOSE,
    COORDINATE_FALLBACK,
}

/** Why a strategy candidate was rejected (debug-only timeline label). */
object BypassRejectReason {
    const val NO_AD_CONTEXT = "NO_AD_CONTEXT"
    const val TOO_LARGE = "TOO_LARGE"
    const val SENSITIVE_ACTIVITY = "SENSITIVE_ACTIVITY"
    const val OUTSIDE_WINDOW = "OUTSIDE_WINDOW"
    const val NEGATIVE_SEMANTIC = "NEGATIVE_SEMANTIC"
    const val SENSITIVE_SEMANTIC = "SENSITIVE_SEMANTIC"
    const val STRATEGY_GATE = "STRATEGY_GATE"
    const val RULE_LEVEL = "RULE_LEVEL"
    const val BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
}

/** Pure text/attribute classifier used by tests and by the engine. */
object BypassExitClassifier {

    /** Skip semantics: 跳过/跳過/Skip with short text. */
    private val skipTextTokens = listOf("跳过", "跳過", "跳 过", "跳 過")
    private val skipDescTokens = listOf("跳过", "跳過")
    private val closeTextTokens = listOf("关闭", "关闭广告", "关闭此广告", "关闭该广告", "關閉", "關閉廣告", "close ad", "close")
    private val closeDescTokens = listOf("关闭", "关闭广告", "關閉", "close")
    private val negativeTokens = listOf("next", "下一步", "完成", "设置", "搜索", "历史记录", "阅读并同意", "跳过片头", "跳过片尾", "跳过视频", "取消", "退出", "帮助")
    private val sensitiveTokens = listOf("支付", "付款", "确认支付", "提交订单", "转账", "验证码", "授权登录", "允许", "安装", "卸载", "同意", "银行卡", "身份认证", "password", "otp", "card", "bank")
    private val adLabelTokens = listOf("广告", "推广", "ad", "ads", "sponsored")

    /**
     * Normalize any text for semantic matching: trim + lowercase. All
     * negative/sensitive/close/skip checks run against the normalized value.
     */
    fun normalize(value: String?): String = value?.trim()?.lowercase().orEmpty()

    /** Whether the node carries an explicit ad label (ad context evidence). */
    fun hasAdLabel(text: String?, description: String?, viewId: String?): Boolean {
        val haystack = listOfNotNull(text, description, viewId).joinToString(" ").lowercase()
        return adLabelTokens.any { haystack.contains(it) }
    }

    fun classify(
        text: String?,
        description: String?,
        viewId: String?,
        className: String?,
        clickable: Boolean,
        width: Int,
        height: Int,
    ): BypassExitCandidateType? {
        val t = normalize(text)
        val d = normalize(description)
        val vid = normalize(viewId)
        val cn = className?.trim()?.lowercase().orEmpty()

        // Negative semantics are hard gates regardless of strategy.
        if (negativeTokens.any { t.contains(it) || d.contains(it) }) return null
        if (sensitiveTokens.any { t.contains(it) || d.contains(it) }) return null

        // Skip wins: mature conservative path.
        if (skipTextTokens.any { t.contains(it) } || t.contains("skip")) {
            return BypassExitCandidateType.SKIP_TEXT
        }
        if (skipDescTokens.any { d.contains(it) }) {
            return BypassExitCandidateType.SKIP_TEXT
        }

        // Explicit close semantics by view id: ad_close / splash_close / close_btn...
        if (vid.isNotBlank() && (vid.contains("ad_close") || vid.contains("splash_close") ||
                vid.contains("close_ad") || vid.contains("close_btn") || vid.contains("close_button") ||
                vid.contains("close_icon") || vid.contains("iv_close") ||
                vid.contains("img_close") || vid.contains("closeview"))
        ) {
            return BypassExitCandidateType.CLOSE_VIEW_ID
        }

        // Explicit close text (normalized, so "Close"/"CLOSE"/"close" all hit).
        if (closeTextTokens.any { t.contains(it) } || closeTextTokens.any { d.contains(it) }) {
            return if (t.isNotBlank()) BypassExitCandidateType.CLOSE_TEXT else BypassExitCandidateType.CLOSE_DESC
        }

        // X / × glyphs.
        if (t == "x" || t == "×" || t == "✕" || t == "✖") {
            return BypassExitCandidateType.CLOSE_ICON
        }
        if (d == "关闭" || d == "close" || d == "x" || d == "×") {
            return BypassExitCandidateType.CLOSE_DESC
        }

        // Small image/view without text inside an ad context: structural.
        if (cn.contains("imageview") || cn.contains("image") || cn == "android.view.view") {
            if (width in 1..160 && height in 1..160) {
                return BypassExitCandidateType.STRUCTURAL_CLOSE
            }
        }
        return null
    }

    fun classifyNode(node: AccessibilityNodeInfo): BypassExitCandidateType? {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val vid = node.viewIdResourceName
        val cn = node.className?.toString()
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return classify(
            text = text,
            description = desc,
            viewId = vid,
            className = cn,
            clickable = node.isClickable,
            width = rect.width(),
            height = rect.height(),
        )
    }
}

/**
 * Host apps that keep generic close/X fallback excluded.
 *
 * Single source: rules/safety_exclusions.json (asset copy
 * bypass_safety_exclusions.json) — see [BypassSafetyConfig]. The built-in
 * fallback list lives there; nothing else defines it.
 */
fun isBypassHighRiskApp(packageName: String?): Boolean =
    BypassSafetyConfig.isHighRisk(packageName)

/** Negative fallback tokens shared by the engine and tests. */
val bypassNegativeFallbackTokens = listOf(
    "next", "下一步", "完成", "设置", "搜索", "历史记录", "阅读并同意",
    "跳过片头", "跳过片尾", "跳过视频", "取消", "退出", "帮助",
)
