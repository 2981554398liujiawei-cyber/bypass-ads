package li.songe.gkd.bypass

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import li.songe.gkd.META
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.store.storeFlow

/**
 * Runtime gate that turns a matched node into an allowed ad-exit action.
 *
 * The GKD matcher still does the selector work; this layer decides, per
 * strategy mode, rule trust, and ad context, whether a *generic* candidate
 * is allowed to run. Curated mature dedicated rules are not gated here.
 */
object BypassStrategyGate {

    fun currentStrategyMode(): BypassAdStrategyMode =
        BypassAdStrategyMode.from(storeFlow.value.bypassAdStrategyMode)

    fun currentPolicy(): BypassStrategyPolicy = currentStrategyMode().policy

    /**
     * Decide whether a candidate may act.
     *
     * @param candidate classified exit type
     * @param policy active mode policy
     * @param rulePolicy identity-derived policy of the matched rule
     * @param contextLevel current ad context (NONE/WEAK/STRONG)
     * @param inWindow whether the window was freshly entered
     */
    fun rejectReason(
        candidate: BypassExitCandidateType,
        packageName: String,
        activityName: String?,
        nodeWidth: Int,
        nodeHeight: Int,
        policy: BypassStrategyPolicy,
        rulePolicy: BypassRulePolicy,
        contextLevel: BypassAdContextLevel,
        inWindow: Boolean,
    ): String? {
        // High-risk hosts: only curated dedicated rules or Bypass-owned
        // overrides may act; generic fallback is always off there.
        if (isBypassHighRiskApp(packageName)) {
            val exempt = rulePolicy.trust == BypassRuleTrust.BUNDLED_DEDICATED ||
                rulePolicy.trust == BypassRuleTrust.BYPASS_OVERRIDE
            return if (exempt) null else BypassRejectReason.SENSITIVE_ACTIVITY
        }

        // Curated dedicated rules run in every mode without candidate gating.
        if (rulePolicy.trust == BypassRuleTrust.BUNDLED_DEDICATED) {
            return null
        }

        // The post-entry ad window applies to generic/override candidates.
        if (!inWindow) {
            return BypassRejectReason.OUTSIDE_WINDOW
        }

        // Size constraint: oversized controls are not close buttons.
        if (nodeWidth > 420 || nodeHeight > 260) {
            return BypassRejectReason.TOO_LARGE
        }

        // Candidate type -> active policy.
        val allowed = when (candidate) {
            BypassExitCandidateType.SKIP_TEXT -> true
            BypassExitCandidateType.CLOSE_TEXT,
            BypassExitCandidateType.CLOSE_DESC,
            -> policy.allowGenericCloseText
            BypassExitCandidateType.CLOSE_VIEW_ID -> policy.allowCloseViewId
            BypassExitCandidateType.CLOSE_ICON -> policy.allowGlyphClose
            BypassExitCandidateType.STRUCTURAL_CLOSE -> policy.allowStructuralNoSemanticClose
            BypassExitCandidateType.COORDINATE_FALLBACK -> policy.allowCoordinateFallback
        }
        if (!allowed) {
            return BypassRejectReason.STRATEGY_GATE
        }

        // Generic close/glyph/structural/coordinate candidates need a strong
        // ad context (skip semantics are always explicit).
        if (candidate != BypassExitCandidateType.SKIP_TEXT &&
            rulePolicy.requiresStrongAdContext &&
            contextLevel != BypassAdContextLevel.STRONG
        ) {
            return BypassRejectReason.NO_AD_CONTEXT
        }
        return null
    }

    /**
     * Whether the current window is inside the post-launch ad window.
     *
     * The window is anchored to actual package / activity entry time (kept by
     * [BypassAdContextTracker]); a service reconnect never opens a new window.
     */
    fun inStartupWindow(packageName: String, startupWindowMs: Long = 15_000L): Boolean {
        val topApp = topActivityFlow.value.appId
        if (topApp != packageName) return false
        val now = System.currentTimeMillis()
        val activityName = topActivityFlow.value.activityId
        val activityTime = BypassAdContextTracker.activityEntryTime(packageName, activityName)
        if (activityTime > 0 && now - activityTime <= startupWindowMs) return true
        val packageTime = BypassAdContextTracker.packageEntryTime(packageName)
        val inWindow = packageTime > 0 && now - packageTime <= startupWindowMs
        if (META.debuggable && !inWindow) {
            Log.d("BypassStrategyGate", "window miss pkg=$packageName act=$activityName activityTime=$activityTime packageTime=$packageTime now=$now")
        }
        return inWindow
    }

    /** Whether an activity is a known mini-program / webview splash host. */
    fun isMiniProgramActivity(packageName: String, activityName: String?): Boolean =
        BypassAdContextTracker.isMiniProgramAdActivity(packageName, activityName)

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
    fun maxExitAttempts(): Int = currentPolicy().maxExitAttempts
}
