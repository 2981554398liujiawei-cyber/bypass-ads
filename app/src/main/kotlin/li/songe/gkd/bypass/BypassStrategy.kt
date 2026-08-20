package li.songe.gkd.bypass

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Three-tier splash exit strategy.
 *
 * The product keeps one rule stack (mature source + Bypass-owned override) and
 * a runtime [BypassStrategyPolicy]; the mode only decides which candidates,
 * which fallbacks, which actions, and how many exit attempts are allowed. It
 * never edits a third-party rule file and never changes rule-source
 * precedence.
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
            CONSERVATIVE -> "只操作高置信度广告退出控件。误触风险最低，但可能漏过部分“关闭/X”广告。"
            AGGRESSIVE -> "同时识别“跳过、关闭”和部分 X 控件，并允许有限重试。成功率更高，存在少量误触风险。"
            CRAZY -> "确认广告上下文后，积极尝试可用退出方式，包括 X、结构控件和受限坐标规则。成功率优先，误触风险最高。"
        }

    val policy: BypassStrategyPolicy
        get() = when (this) {
            CONSERVATIVE -> BypassStrategyPolicy(
                allowGenericCloseText = false,
                allowCloseViewId = false,
                allowStructuralCloseIcon = false,
                allowCoordinateFallback = false,
                maxExitAttempts = 1,
            )
            AGGRESSIVE -> BypassStrategyPolicy(
                allowGenericCloseText = true,
                allowCloseViewId = true,
                allowStructuralCloseIcon = true,
                allowCoordinateFallback = false,
                maxExitAttempts = 2,
            )
            CRAZY -> BypassStrategyPolicy(
                allowGenericCloseText = true,
                allowCloseViewId = true,
                allowStructuralCloseIcon = true,
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
    val allowStructuralCloseIcon: Boolean,
    val allowCoordinateFallback: Boolean,
    val maxExitAttempts: Int,
)

/** What kind of ad-exit control a matched node looks like. */
enum class BypassExitCandidateType {
    SKIP_TEXT,
    CLOSE_TEXT,
    CLOSE_DESC,
    CLOSE_VIEW_ID,
    CLOSE_ICON,
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

    fun classify(
        text: String?,
        description: String?,
        viewId: String?,
        className: String?,
        clickable: Boolean,
        width: Int,
        height: Int,
    ): BypassExitCandidateType? {
        val t = text?.trim().orEmpty().lowercase()
        val d = description?.trim().orEmpty().lowercase()
        val vid = viewId?.lowercase().orEmpty()
        val cn = className?.lowercase().orEmpty()

        // Negative semantics are hard gates regardless of strategy.
        val tRaw = text?.trim().orEmpty()
        val dRaw = description?.trim().orEmpty()
        if (negativeTokens.any { tRaw.contains(it) || dRaw.contains(it) }) return null
        if (sensitiveTokens.any { tRaw.contains(it) || dRaw.contains(it) }) return null

        // Skip wins: mature conservative path.
        if (skipTextTokens.any { tRaw.contains(it) } || t.contains("skip")) {
            return BypassExitCandidateType.SKIP_TEXT
        }
        if (skipDescTokens.any { dRaw.contains(it) }) {
            return BypassExitCandidateType.SKIP_TEXT
        }

        // Explicit close semantics by view id: ad_close / splash_close / close_btn...
        if (vid.isNotBlank() && (vid.contains("ad_close") || vid.contains("splash_close") ||
                vid.contains("close_ad") || vid.contains("close_btn") || vid.contains("close_button") ||
                vid.contains("close_icon") || vid.contains("close_icon") || vid.contains("iv_close") ||
                vid.contains("img_close") || vid.contains("closeview"))
        ) {
            return BypassExitCandidateType.CLOSE_VIEW_ID
        }

        // Explicit close text. Match against both the raw and the
        // lower-cased value so "Close" and "关闭广告" both resolve.
        if (closeTextTokens.any { tRaw.contains(it) || t.contains(it) } ||
            closeTextTokens.any { dRaw.contains(it) || d.contains(it) }
        ) {
            return if (t.isNotBlank()) BypassExitCandidateType.CLOSE_TEXT else BypassExitCandidateType.CLOSE_DESC
        }

        // X / × glyphs.
        if (tRaw == "x" || tRaw == "×" || tRaw == "✕" || tRaw == "✖" || tRaw == "X") {
            return BypassExitCandidateType.CLOSE_ICON
        }
        if (dRaw == "关闭" || dRaw == "close" || dRaw == "x" || dRaw == "×") {
            return BypassExitCandidateType.CLOSE_DESC
        }

        // Small image/view without text inside an ad context.
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

/** Host apps that keep generic close/X fallback excluded. */
val bypassHighRiskApps = setOf(
    "com.eg.android.AlipayGphone", // Alipay pages can contain real payment controls
    "com.tencent.mm", // WeChat host: handled by dedicated miniprogram rules only
    "com.android.settings",
    "com.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.android.systemui",
    "com.miui.securitycenter",
    "com.miui.securitycore",
    "com.miui.packageinstaller",
    "com.google.android.permissioncontroller",
    // Password managers / authenticators
    "com.lastpass.lpandroid",
    "com.dashlane",
    "com.oneplus.bpkb",
    "com.authenticator.auth",
    // Banks and securities
    "com.chinamworld.main", // 工商银行
    "com.android.bankabc", // 农业银行
    "com.ccb.life", // 建设银行
    "com.unionpay", // 云闪付
    "com.pingan.pinganwifi",
    "com.eg.android.bankpay",
)

/** Whether a package should keep the generic strategy fallback off. */
fun isBypassHighRiskApp(packageName: String?): Boolean =
    packageName != null && bypassHighRiskApps.contains(packageName)

/** Negative fallback tokens shared by the engine and tests. */
val bypassNegativeFallbackTokens = listOf(
    "next", "下一步", "完成", "设置", "搜索", "历史记录", "阅读并同意",
    "跳过片头", "跳过片尾", "跳过视频", "取消", "退出", "帮助",
)

/**
 * Bypass-owned rules can declare the minimum strategy level that may run
 * them via a rule name suffix. Examples:
 *   "Bypass-WeChat-Close-Aggressive" -> needs AGGRESSIVE
 *   "Bypass-X-Crazy"                  -> needs CRAZY
 * Rules without a marker run in every mode (e.g. curated skip rules).
 */
fun bypassRequiredStrategyLevel(ruleName: String?): Int = when {
    ruleName.orEmpty().contains("Crazy", ignoreCase = true) -> 2
    ruleName.orEmpty().contains("Aggressive", ignoreCase = true) -> 1
    else -> 0
}
