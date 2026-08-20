package li.songe.gkd.bypass

import android.view.accessibility.AccessibilityNodeInfo
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

    /** Last package seen through the event stream; used to distinguish a real
     * package transition (refresh package entry time) from an activity change
     * or a service reconnect on the same package (keep it). */
    @Volatile
    private var lastEventPackage: String? = null

    /** Called from A11yState.updateTopActivity on every top change. */
    fun onTopActivityChanged(packageName: String, activityName: String?, time: Long) {
        if (lastEventPackage != packageName) {
            // A real package transition (P0-5: REAL_PACKAGE_CHANGE) must
            // refresh the package entry time, even when the package returns
            // after another app was foreground (A -> B -> A).
            packageEntryTimes[packageName] = time
            lastEventPackage = packageName
        }
        // Same package, new activity (REAL_ACTIVITY_CHANGE): refresh only the
        // activity entry time (WeChat LauncherUI -> AppBrandUI is a fresh
        // activity window while the package entry time stays put).
        if (!activityName.isNullOrBlank()) {
            activityEntryTimes[windowKey(packageName, activityName)] = time
        }
    }

    /** Called when the top app changes (package-level transition). */
    fun onPackageChanged(packageName: String, time: Long) {
        packageEntryTimes[packageName] = time
        lastEventPackage = packageName
    }

    /**
     * Engine-side observation: the matcher saw [packageName] as the top app
     * (from a fresh active-window read). This anchors the ad window even when
     * the OEM swallows the launch accessibility event. Only a REAL package
     * transition re-opens the window; a service reconnect (empty baseline)
     * or a re-read of the same app never does (P0-5).
     */
    fun onAppObserved(
        packageName: String,
        time: Long,
        type: BypassWindowAnchorObservationType = BypassWindowAnchorObservationType.REAL_PACKAGE_CHANGE,
    ) {
        if (type == BypassWindowAnchorObservationType.REAL_PACKAGE_CHANGE) {
            packageEntryTimes[packageName] = time
        }
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

    /**
     * Bounded window scan for ad-label evidence (广告 / ad / sponsored). This
     * is context evidence only: it walks at most [MAX_SCAN_NODES] nodes of
     * the already-fetched root, never a second scanner.
     */
    fun noteAdLabelFromWindow(root: AccessibilityNodeInfo, packageName: String, activityName: String?) {
        scanForAdLabel(root)?.let {
            noteAdLabel(packageName, activityName)
        }
    }

    /**
     * One-shot ad-label scan on a fresh root. Unlike [noteAdLabelFromWindow]
     * this never persists window evidence: the OutcomeVerifier uses it AFTER
     * an action, where the question is "is there ad evidence RIGHT NOW",
     * not "was there ever any". A still-visible 广告/ad label means the ad
     * overlay is still up.
     */
    fun scanFreshAdLabel(root: AccessibilityNodeInfo): Boolean =
        scanForAdLabel(root) != null

    /**
     * Session-scoped fresh ad-label scan (P0-2): true only when an ad label
     * (广告 / ad / sponsored) is visible NEAR the acted candidate's bounds on
     * the fresh root. Unrelated banners / labels elsewhere on the page (e.g.
     * a star-charge page banner) never count as "the ad is still up".
     */
    fun scanFreshAdLabelNear(root: AccessibilityNodeInfo, evidenceBounds: String): Boolean {
        val evidence = BypassOutcomeVerifier.parseBounds(evidenceBounds) ?: return false
        var visited = 0
        val MAX_SCAN_NODES = 80
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (visited++ >= MAX_SCAN_NODES) return false
            if (BypassExitClassifier.hasAdLabel(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                    node.viewIdResourceName,
                )
            ) {
                val rect = android.graphics.Rect().also {
                    runCatching { node.getBoundsInScreen(it) }.getOrNull()
                }
                val labelBounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
                if (BypassOutcomeVerifier.sameAdRegion(evidence, labelBounds)) return true
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            repeat(childCount.coerceAtMost(24)) { index ->
                runCatching { node.getChild(index) }.getOrNull()?.let {
                    if (visit(it)) return true
                }
            }
            return false
        }
        return runCatching { visit(root) }.getOrDefault(false)
    }

    private fun scanForAdLabel(root: AccessibilityNodeInfo): String? {
        var visited = 0
        val MAX_SCAN_NODES = 80
        fun visit(node: AccessibilityNodeInfo): String? {
            if (visited++ >= MAX_SCAN_NODES) return null
            if (BypassExitClassifier.hasAdLabel(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                    node.viewIdResourceName,
                )
            ) {
                return "ad_label"
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            repeat(childCount.coerceAtMost(24)) { index ->
                runCatching { node.getChild(index) }.getOrNull()?.let {
                    visit(it)?.let { label -> return label }
                }
            }
            return null
        }
        return runCatching { visit(root) }.getOrDefault(null)
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
     * STRONG only with REAL ad evidence: an explicit skip candidate already
     * seen in this window, or an explicit ad label (广告 / ad / sponsored).
     * WEAK when the window is a known mini-program shell (AppBrandUI /
     * XRiverActivity) or a close candidate appeared during a fresh entry but
     * nothing else confirms an ad — the mini-program shell alone is NOT ad
     * proof (star-charge pages, member pages and normal in-mini-program pages
     * all run inside AppBrandUI). NONE otherwise.
     */
    fun evaluate(
        packageName: String,
        activityName: String?,
        now: Long = System.currentTimeMillis(),
    ): BypassAdContextLevel {
        val key = windowKey(packageName, activityName)
        // Real ad evidence first: an explicit skip candidate or an ad label
        // in this window already confirms an ad (P0-3).
        if (windowSkipSeen[key] == true || windowAdLabelSeen[key] == true) {
            return BypassAdContextLevel.STRONG
        }
        // A mini-program shell is only weak evidence: the ad itself may or
        // may not be present.
        if (isMiniProgramAdActivity(packageName, activityName)) {
            return BypassAdContextLevel.WEAK
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

    /** Test hook: wipe all tracker state (JVM unit tests). */
    fun clearForTest() {
        packageEntryTimes.clear()
        activityEntryTimes.clear()
        windowSkipSeen.clear()
        windowCloseSeen.clear()
        windowAdLabelSeen.clear()
    }
}

/**
 * How a fresh top-app observation relates to the startup window (P0-5).
 *
 * A service reconnect starts with an empty baseline: the first fresh root
 * read after reconnect only ESTABLISHES the baseline and is not evidence of
 * a launch — it must never refresh the package entry time
 * (SERVICE_RECONNECT). A genuine A -> B package transition (or a
 * stale-baseline reveal of a different app) does re-anchor
 * (REAL_PACKAGE_CHANGE). Real package transitions are also delivered through
 * the event stream (REAL_ACTIVITY_CHANGE when only the activity changed).
 */
enum class BypassWindowAnchorObservationType {
    /** First fresh observation after a reconnect / engine start: baseline only. */
    SERVICE_RECONNECT,

    /** A real package transition (observed or event-driven). */
    REAL_PACKAGE_CHANGE,

    /** Same package, new activity (event-driven only). */
    REAL_ACTIVITY_CHANGE,

    /** Re-observation of the same package that is still foreground. */
    ENGINE_FALLBACK_OBSERVATION,
}

object BypassWindowAnchor {
    /**
     * Classify a fresh top-app observation. The empty baseline is no longer
     * treated as "must re-anchor": it is a reconnect baseline.
     */
    fun classifyObservation(previousObservedApp: String, freshApp: String): BypassWindowAnchorObservationType =
        when {
            previousObservedApp.isEmpty() -> BypassWindowAnchorObservationType.SERVICE_RECONNECT
            previousObservedApp != freshApp -> BypassWindowAnchorObservationType.REAL_PACKAGE_CHANGE
            else -> BypassWindowAnchorObservationType.ENGINE_FALLBACK_OBSERVATION
        }

    /**
     * Backward-compatible predicate: only a REAL package transition may
     * (re)anchor the startup window.
     */
    fun shouldReanchorOnObservation(previousObservedApp: String, freshApp: String): Boolean =
        classifyObservation(previousObservedApp, freshApp) ==
            BypassWindowAnchorObservationType.REAL_PACKAGE_CHANGE
}
