package li.songe.gkd.bypass

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import li.songe.gkd.a11y.A11yContext
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.data.ResolvedRule

/**
 * Outcome of one performed action, decided from a fresh state read (never
 * from the stale matched node alone).
 */
enum class BypassOutcome {
    SUCCESS_CONFIRMED,
    ACTION_NO_EFFECT,
    UNRESOLVED,
    MISCLICK_SUSPECTED,
}

/**
 * Verifies what an action actually did by re-reading the active window with
 * the same GKD selector machinery (no second scanner). The result is the
 * session-level truth:
 *
 *  - exit candidate gone AND ad context no longer strong  -> SUCCESS_CONFIRMED
 *  - exit candidate still present / ad context still holds -> ACTION_NO_EFFECT
 *  - cannot read a fresh window                           -> UNRESOLVED
 *  - jumped to an external landing package                -> MISCLICK_SUSPECTED
 */
object BypassOutcomeVerifier {

    private const val VERIFY_DELAY_MS = 300L

    /** Browsers / app stores / ad landing hosts. */
    val externalLandingPackages = setOf(
        "com.android.chrome",
        "com.android.browser",
        "com.miui.browser",
        "com.tencent.mtt",
        "com.UCMobile",
        "com.quark.browser",
        "com.heytap.browser",
        "com.vivo.browser",
        "com.oplus.browser",
        "com.android.vending",
        "com.xiaomi.market",
        "com.tencent.android.qqdownloader",
        "com.huawei.appmarket",
        "com.oppo.market",
        "com.vivo.market",
        "com.bbk.appstore",
    )

    suspend fun verify(
        rule: ResolvedRule,
        target: AccessibilityNodeInfo,
        packageName: String,
        activityName: String?,
        a11yContext: A11yContext,
        freshWindowProvider: suspend () -> AccessibilityNodeInfo?,
    ): BypassOutcome {
        // 1. External landing check first: did the top app move somewhere
        //    that a click should never take the user?
        val top = topActivityFlow.value
        if (top.appId != packageName) {
            return if (isExternalLanding(top.appId)) BypassOutcome.MISCLICK_SUSPECTED
            else BypassOutcome.UNRESOLVED
        }

        // 2. Give the target app a moment to react.
        delay(VERIFY_DELAY_MS)

        // 3. Fresh state: re-read the active window and re-query the same rule.
        val freshRoot = freshWindowProvider() ?: return BypassOutcome.UNRESOLVED
        val stillMatches = runCatching {
            a11yContext.queryRule(rule, freshRoot) != null
        }.getOrDefault(true)

        // 4. Ad context on the fresh state.
        val context = BypassAdContextTracker.evaluate(packageName, activityName)
        return decide(
            stillMatches = stillMatches,
            context = context,
            topChangedToExternal = isExternalLanding(top.appId),
            topChanged = top.appId != packageName,
        )
    }

    /**
     * Pure decision logic (unit-testable):
     *
     *  - top moved to an external landing package  -> MISCLICK_SUSPECTED
     *  - top moved elsewhere (not external)        -> UNRESOLVED
     *  - exit candidate still matches              -> ACTION_NO_EFFECT
     *  - candidate gone but ad context still holds -> ACTION_NO_EFFECT
     *  - candidate gone + no strong context        -> SUCCESS_CONFIRMED
     *  - otherwise                                 -> UNRESOLVED
     */
    fun decide(
        stillMatches: Boolean,
        context: BypassAdContextLevel,
        topChanged: Boolean,
        topChangedToExternal: Boolean,
    ): BypassOutcome = when {
        topChanged && topChangedToExternal -> BypassOutcome.MISCLICK_SUSPECTED
        topChanged -> BypassOutcome.UNRESOLVED
        stillMatches -> BypassOutcome.ACTION_NO_EFFECT
        context == BypassAdContextLevel.STRONG -> BypassOutcome.ACTION_NO_EFFECT
        !stillMatches -> BypassOutcome.SUCCESS_CONFIRMED
        else -> BypassOutcome.UNRESOLVED
    }

    fun isExternalLanding(packageName: String): Boolean = externalLandingPackages.contains(packageName)
}
