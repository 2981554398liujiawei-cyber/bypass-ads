package li.songe.gkd.bypass

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

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
 * re-querying ad evidence with the same GKD selector machinery (no second
 * scanner). The result is the session-level truth.
 *
 * Mini-program shells are NOT treated as ad proof: WeChat keeps running the
 * mini-program inside AppBrandUI after the ad closes, so "AppBrandUI is
 * foreground" alone proves nothing. The verifier only accepts fresh
 * evidence gathered AFTER the action:
 *
 *   Action -> delay -> re-read top package/activity (P0-5: never the
 *   pre-delay cached state) -> fresh root -> fresh ad-exit candidates +
 *   fresh ad-label scan.
 *
 *  - top moved to an external landing package (browser / store / launcher)
 *    after the delay               -> MISCLICK_SUSPECTED
 *  - top moved elsewhere           -> UNRESOLVED
 *  - an ad exit candidate or an ad label still matches the fresh root
 *                                   -> ACTION_NO_EFFECT
 *  - same app, no ad evidence      -> SUCCESS_CONFIRMED
 *  - cannot determine              -> UNRESOLVED
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

    /**
     * @param packageName the package the action ran in (the original top app)
     * @param freshWindowProvider fresh active-window read after the delay
     * @param topFallbackProvider fallback fresh top-package read when the
     *        window read fails (e.g. the event-driven topActivityFlow)
     * @param freshAdCandidateProvider fresh ad-exit candidate query
     * @param freshAdLabelProvider one-shot ad-label scan (ad overlay evidence,
     *        never persisted window state)
     */
    suspend fun verify(
        packageName: String,
        freshWindowProvider: suspend () -> AccessibilityNodeInfo?,
        topFallbackProvider: suspend () -> String?,
        freshAdCandidateProvider: suspend (AccessibilityNodeInfo) -> Boolean,
        freshAdLabelProvider: suspend (AccessibilityNodeInfo) -> Boolean,
    ): BypassOutcome {
        // 1. Give the target app a moment to react — and a possible external
        //    jump (browser/store/launcher) a moment to show up.
        delay(VERIFY_DELAY_MS)

        // 2. Re-read the top package/activity AFTER the delay. The cached
        //    pre-delay top state must never decide the outcome (P0-5): a
        //    performAction returning true followed 200ms later by a jump to
        //    Chrome must be MISCLICK_SUSPECTED, never SUCCESS_CONFIRMED.
        val freshRoot = freshWindowProvider()
        val freshTopPkg = freshRoot?.packageName?.toString() ?: topFallbackProvider()
        if (freshTopPkg != null && freshTopPkg != packageName) {
            return decide(
                freshAdCandidateExists = false,
                freshAdEvidence = false,
                topChanged = true,
                topChangedToExternal = isExternalLanding(freshTopPkg),
            )
        }

        // 3. Fresh evidence on the same window: ad exit candidates and ad
        //    labels. A mini-program shell being foreground is NOT evidence.
        if (freshRoot == null) return BypassOutcome.UNRESOLVED
        val freshAdCandidate = runCatching {
            freshAdCandidateProvider(freshRoot)
        }.getOrDefault(true)
        val freshAdEvidence = runCatching {
            freshAdLabelProvider(freshRoot)
        }.getOrDefault(false)

        return decide(
            freshAdCandidateExists = freshAdCandidate,
            freshAdEvidence = freshAdEvidence,
            topChanged = false,
            topChangedToExternal = false,
        )
    }

    /**
     * Pure decision logic (unit-testable):
     *
     *  - top moved to an external landing package  -> MISCLICK_SUSPECTED
     *  - top moved elsewhere (not external)        -> UNRESOLVED
     *  - an ad exit candidate still matches        -> ACTION_NO_EFFECT
     *  - an ad label is still visible on the fresh root (ad overlay) ->
     *    ACTION_NO_EFFECT
     *  - otherwise (same app, no ad evidence)      -> SUCCESS_CONFIRMED
     */
    fun decide(
        freshAdCandidateExists: Boolean,
        freshAdEvidence: Boolean,
        topChanged: Boolean,
        topChangedToExternal: Boolean,
    ): BypassOutcome = when {
        topChanged && topChangedToExternal -> BypassOutcome.MISCLICK_SUSPECTED
        topChanged -> BypassOutcome.UNRESOLVED
        freshAdCandidateExists -> BypassOutcome.ACTION_NO_EFFECT
        freshAdEvidence -> BypassOutcome.ACTION_NO_EFFECT
        else -> BypassOutcome.SUCCESS_CONFIRMED
    }

    fun isExternalLanding(packageName: String): Boolean = externalLandingPackages.contains(packageName)

    /** Accessor kept for tests that want to pin the verifier delay. */
    internal fun verifyDelayMs(): Long = VERIFY_DELAY_MS
}
