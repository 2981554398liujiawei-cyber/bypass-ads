package li.songe.gkd.bypass

import java.util.concurrent.ConcurrentHashMap

/**
 * Three-level ad context. Deliberately coarse: no fake AI scores. The
 * levels only gate whether generic close/glyph/structural/coordinate
 * candidates may act.
 */
enum class BypassAdContextLevel {
    NONE,
    WEAK,
    STRONG,
}

/**
 * Tracks ad-context evidence for the current top window and remembers
 * package / activity entry times separately. A service reconnect is NOT a
 * launch: it never opens a new splash window by itself (the entry-time maps
 * only move when the top app or activity actually changes).
 */
object BypassAdContextTracker {

    private const val CONTEXT_WINDOW_MS = 15_000L

    private val packageEntryTimes = ConcurrentHashMap<String, Long>()
    private val activityEntryTimes = ConcurrentHashMap<String, Long>() // key: "$pkg|$activity"

    /** Per-window evidence: "$pkg|$activity" -> flags. */
    private val windowSkipSeen = ConcurrentHashMap<String, Boolean>()
    private val windowCloseSeen = ConcurrentHashMap<String, Boolean>()
    private val windowAdLabelSeen = ConcurrentHashMap<String, Boolean>()

    /** Called from A11yState.updateTopActivity on every top change. */
    fun onTopActivityChanged(packageName: String, activityName: String?, time: Long) {
        packageEntryTimes.putIfAbsent(packageName, time)
        if (!activityName.isNullOrBlank()) {
            // Overwrite: an activity (re)entry is a fresh window even when the
            // package is unchanged (WeChat LauncherUI -> AppBrandUI).
            activityEntryTimes[windowKey(packageName, activityName)] = time
        }
    }

    /** Called when the top app changes (package-level transition). */
    fun onPackageChanged(packageName: String, time: Long) {
        packageEntryTimes[packageName] = time
    }

    fun packageEntryTime(packageName: String): Long = packageEntryTimes[packageName] ?: 0L

    fun activityEntryTime(packageName: String, activityName: String?): Long =
        activityName?.let { activityEntryTimes[windowKey(packageName, it)] } ?: 0L

    /** Record that the current window produced an ad candidate. */
    fun noteCandidate(packageName: String, activityName: String?, candidate: BypassExitCandidateType?) {
        if (candidate == null) return
        val key = windowKey(packageName, activityName)
        if (candidate == BypassExitCandidateType.SKIP_TEXT) {
            windowSkipSeen[key] = true
        } else {
            windowCloseSeen[key] = true
        }
    }

    /** Record that an explicit ad label (广告 / ad / ads) was seen. */
    fun noteAdLabel(packageName: String, activityName: String?) {
        windowAdLabelSeen[windowKey(packageName, activityName)] = true
    }

    fun resetWindow(packageName: String, activityName: String?) {
        val key = windowKey(packageName, activityName)
        windowSkipSeen.remove(key)
        windowCloseSeen.remove(key)
        windowAdLabelSeen.remove(key)
    }

    /**
     * Known mini-program / webview ad hosts. Presence of one of these
     * activities is itself STRONG ad context (WeChat/ Alipay mini-program
     * ads always live inside these webview shells).
     */
    fun isMiniProgramAdActivity(packageName: String, activityName: String?): Boolean {
        if (activityName.isNullOrBlank()) return false
        return when (packageName) {
            "com.tencent.mm" ->
                activityName.contains(".plugin.appbrand.ui.AppBrandUI") ||
                    activityName.contains(".plugin.appbrand.launching.AppBrandLaunchProxyUI") ||
                    activityName.contains("AppBrandUI")
            "com.eg.android.AlipayGphone" ->
                activityName.contains("XRiverActivity") ||
                    activityName.contains("NebulaActivity") ||
                    activityName.contains("nebula")
            else -> false
        }
    }

    /**
     * Evaluate the ad context of the current window.
     *
     * STRONG when: known mini-program ad activity, an explicit ad label was
     * seen, or a skip candidate was already seen in this window (the ad was
     * already confirmed). WEAK when a close candidate appeared during a fresh
     * entry but nothing else confirms an ad. NONE otherwise.
     */
    fun evaluate(
        packageName: String,
        activityName: String?,
        now: Long = System.currentTimeMillis(),
    ): BypassAdContextLevel {
        if (isMiniProgramAdActivity(packageName, activityName)) return BypassAdContextLevel.STRONG
        val key = windowKey(packageName, activityName)
        if (windowSkipSeen[key] == true || windowAdLabelSeen[key] == true) {
            return BypassAdContextLevel.STRONG
        }
        val freshEntry = freshEntry(packageName, activityName, now)
        if (windowCloseSeen[key] == true && freshEntry) {
            return BypassAdContextLevel.WEAK
        }
        return BypassAdContextLevel.NONE
    }

    /** Whether the window was entered recently (post-launch window). */
    fun freshEntry(packageName: String, activityName: String?, now: Long): Boolean {
        val activityTime = activityEntryTime(packageName, activityName)
        if (activityTime > 0 && now - activityTime <= CONTEXT_WINDOW_MS) return true
        val packageTime = packageEntryTime(packageName)
        return packageTime > 0 && now - packageTime <= CONTEXT_WINDOW_MS
    }

    private fun windowKey(packageName: String, activityName: String?) = "$packageName|${activityName ?: ""}"
}
