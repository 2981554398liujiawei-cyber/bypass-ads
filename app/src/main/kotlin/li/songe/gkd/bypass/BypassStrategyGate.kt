package li.songe.gkd.bypass

import android.view.accessibility.AccessibilityNodeInfo
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.service.A11yService
import li.songe.gkd.store.storeFlow

/**
 * Runtime gate that turns a matched node into an allowed ad-exit action.
 *
 * The GKD matcher still does the selector work; this layer only decides, per
 * strategy mode and ad context, whether a *generic* candidate is allowed to
 * run. Dedicated mature rules and Bypass-owned precise overrides are not
 * gated here (they were already curated).
 */
object BypassStrategyGate {

    /** A dedicated rule: always allowed, no gate. */
    fun isDedicatedRuleAllowed(groupName: String?): Boolean = true

    fun currentStrategyLevel(): Int = storeFlow.value.let {
        BypassAdStrategyMode.from(it.bypassAdStrategyMode).ordinal
    }

    /** Whether a marker-carrying Bypass-owned rule may run under the active mode. */
    fun isRuleAllowedByStrategy(ruleName: String?): Boolean =
        bypassRequiredStrategyLevel(ruleName) <= currentStrategyLevel()

    /**
     * Decide whether a generic (fallback) candidate may act.
     * @param candidate classified exit type
     * @param packageName current top app
     * @param activityName current top activity (may be null)
     * @param nodeWidth/nodeHeight visual size of the candidate
     * @return null when allowed, otherwise a reject reason label
     */
    fun rejectReason(
        candidate: BypassExitCandidateType,
        packageName: String,
        activityName: String?,
        nodeWidth: Int,
        nodeHeight: Int,
        startupWindowMs: Long = 15_000L,
        appHasDedicatedRule: Boolean = false,
        inWindow: Boolean? = null,
        policy: BypassStrategyPolicy? = null,
    ): String? {
        // Hard gates that apply to every mode.
        if (isBypassHighRiskApp(packageName)) {
            // A high-risk host may still run its own dedicated precise rules,
            // but never the generic fallback.
            return if (appHasDedicatedRule) null else BypassRejectReason.SENSITIVE_ACTIVITY
        }
        val effectivePolicy = policy ?: storeFlow.value.let {
            BypassAdStrategyMode.from(it.bypassAdStrategyMode).policy
        }
        val effectiveWindow = inWindow ?: inStartupWindow(packageName, startupWindowMs)
        // 广告窗口：启动后 15 秒内才允许通用 fallback
        if (!effectiveWindow) {
            return BypassRejectReason.OUTSIDE_WINDOW
        }
        // 尺寸约束：过大控件不可能是关闭按钮
        if (nodeWidth > 420 || nodeHeight > 260) {
            return BypassRejectReason.TOO_LARGE
        }
        return when (candidate) {
            BypassExitCandidateType.SKIP_TEXT -> null
            BypassExitCandidateType.CLOSE_TEXT,
            BypassExitCandidateType.CLOSE_DESC,
            -> if (effectivePolicy.allowGenericCloseText) null else BypassRejectReason.STRATEGY_GATE
            BypassExitCandidateType.CLOSE_VIEW_ID -> if (effectivePolicy.allowCloseViewId) null else BypassRejectReason.STRATEGY_GATE
            BypassExitCandidateType.CLOSE_ICON,
            BypassExitCandidateType.STRUCTURAL_CLOSE,
            -> if (effectivePolicy.allowStructuralCloseIcon) null else BypassRejectReason.STRATEGY_GATE
            BypassExitCandidateType.COORDINATE_FALLBACK -> if (effectivePolicy.allowCoordinateFallback) null else BypassRejectReason.STRATEGY_GATE
        }
    }

    /** Whether the current app is inside the post-launch splash window. */
    fun inStartupWindow(packageName: String, startupWindowMs: Long = 15_000L): Boolean {
        val topApp = topActivityFlow.value.appId
        if (topApp != packageName) return false
        val now = System.currentTimeMillis()
        val appChange = li.songe.gkd.a11y.appChangeTime
        if (appChange > 0 && now - appChange <= startupWindowMs) return true
        // The engine may have (re)connected while the target app was already
        // foreground (e.g. adb cold start, service restart). The service
        // connection timestamp is then the effective splash-window baseline.
        val connectedAt = A11yService.lastConnectedAt.value
        return connectedAt > 0 && now - connectedAt <= startupWindowMs
    }

    /** Whether an activity is a known mini-program / webview splash host. */
    fun isMiniProgramActivity(packageName: String, activityName: String?): Boolean {
        if (activityName == null) return false
        return when (packageName) {
            "com.tencent.mm" -> activityName.contains(".plugin.appbrand.ui.AppBrandUI") ||
                activityName.contains(".plugin.appbrand.launching.AppBrandLaunchProxyUI") ||
                activityName.contains("AppBrandUI")
            "com.eg.android.AlipayGphone" -> activityName.contains("XRiverActivity") ||
                activityName.contains("NebulaActivity") ||
                activityName.contains("nebula")
            else -> false
        }
    }

    /** Whether a candidate node is visible to user and inside the screen. */
    fun isSaneCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        if (rect.isEmpty) return false
        val display = li.songe.gkd.app.resources.displayMetrics
        if (rect.right <= 0 || rect.bottom <= 0) return false
        if (rect.left >= display.widthPixels || rect.top >= display.heightPixels) return false
        return true
    }

    /** How many exit attempts remain for the current mode. */
    fun maxExitAttempts(): Int = storeFlow.value.let {
        BypassAdStrategyMode.from(it.bypassAdStrategyMode).policy.maxExitAttempts
    }
}
