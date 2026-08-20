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
 * Verifies what an action actually did by re-reading the active window and
 * re-querying ad candidates with the same GKD selector machinery (no second
 * scanner). The result is the session-level truth:
 *
 *  - jumped to an external landing package      -> MISCLICK_SUSPECTED
 *  - top app changed elsewhere                  -> UNRESOLVED
 *  - any ad candidate still matches fresh state -> ACTION_NO_EFFECT
 *  - mini-program ad shell still foreground     -> ACTION_NO_EFFECT
 *  - otherwise                                  -> SUCCESS_CONFIRMED
 */
object BypassOutcomeVerifier {

    private const val VERIFY_DELAY_MS = 300L

    /** Browsers / app stores / launchers / ad landing hosts. */
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
        // Launchers: an ad-exit action that throws the user to the home
        // screen did not close an ad -> misclick-suspected, stop.
        "com.miui.home",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.android.launcher",
    )

    suspend fun verify(
        rule: ResolvedRule,
        target: AccessibilityNodeInfo,
        packageName: String,
        activityName: String?,
        a11yContext: A11yContext,
        freshWindowProvider: suspend () -> AccessibilityNodeInfo?,
        freshAdCandidateProvider: suspend (AccessibilityNodeInfo) -> Boolean,
    ): BypassOutcome {
        // 1. External landing check first: did the top app move somewhere
        //    that a click should never take the user?
        val top = topActivityFlow.value
        if (top.appId != packageName) {
            return decide(
                freshAdCandidateExists = false,
                shellContextStrong = false,
                topChanged = true,
                topChangedToExternal = isExternalLanding(top.appId),
            )
        }

        // 2. Give the target app a moment to react.
        delay(VERIFY_DELAY_MS)

        // 3. Fresh state: re-read the active window, re-query ad candidates.
        val freshRoot = freshWindowProvider() ?: return BypassOutcome.UNRESOLVED
        val freshAdCandidate = runCatching {
            freshAdCandidateProvider(freshRoot)
        }.getOrDefault(true)
        // The mini-program / webview ad shell is still foreground.
        val shellContextStrong = BypassAdContextTracker.isMiniProgramAdActivity(packageName, activityName)

        return decide(
            freshAdCandidateExists = freshAdCandidate,
            shellContextStrong = shellContextStrong,
            topChanged = false,
            topChangedToExternal = false,
        )
    }

    /**
     * Pure decision logic (unit-testable):
     *
     *  - top moved to an external landing package  -> MISCLICK_SUSPECTED
     *  - top moved elsewhere (not external)        -> UNRESOLVED
     *  - an ad candidate still matches fresh state -> ACTION_NO_EFFECT
     *  - the mini-program ad shell still holds     -> ACTION_NO_EFFECT
     *  - otherwise                                 -> SUCCESS_CONFIRMED
     */
    fun decide(
        freshAdCandidateExists: Boolean,
        shellContextStrong: Boolean,
        topChanged: Boolean,
        topChangedToExternal: Boolean,
    ): BypassOutcome = when {
        topChanged && topChangedToExternal -> BypassOutcome.MISCLICK_SUSPECTED
        topChanged -> BypassOutcome.UNRESOLVED
        freshAdCandidateExists -> BypassOutcome.ACTION_NO_EFFECT
        shellContextStrong -> BypassOutcome.ACTION_NO_EFFECT
        else -> BypassOutcome.SUCCESS_CONFIRMED
    }

    fun isExternalLanding(packageName: String): Boolean = externalLandingPackages.contains(packageName)
}
