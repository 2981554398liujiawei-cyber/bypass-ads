package app.bypassads.accessibility

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.bypassads.core.model.IntRect
import app.bypassads.core.model.UiNodeSnapshot
import app.bypassads.core.model.UiSnapshot

data class CaptureResult(
    val snapshot: UiSnapshot,
    /** Window identity token from the real AccessibilityWindowInfo ids (M2.2 P1-2). */
    val windowContextToken: Long,
    val metrics: CaptureMetrics,
)

data class CaptureMetrics(
    val windowsTraversed: Int,
    val nodesVisited: Int,
    val maxDepth: Int,
    val rootAcquireMaxMs: Long,
    val childQueryMaxMs: Long,
    val budgetHit: Boolean,
    val budgetReason: String?,
)

/**
 * The 75 ms deadline is cooperative: it is checked before a new window, root, node or child
 * query. Android cannot interrupt a node query which has already blocked in an OEM service.
 */
class NodeSnapshotBuilder(private val elapsedMs: () -> Long = SystemClock::elapsedRealtime) {
    fun capture(
        windows: List<AccessibilityWindowInfo>,
        packageHint: String?,
        screenWidth: Int,
        screenHeight: Int,
    ): CaptureResult {
        val startedAtMs = elapsedMs()
        val state = CaptureState(startedAtMs)
        val nodes = mutableListOf<UiNodeSnapshot>()
        var firstPackage: String? = null

        for (window in windows) {
            if (!state.canQuery()) break
            state.windowsTraversed++
            val rootStartedAtMs = elapsedMs()
            // Explicitly disable descendant prefetch for bounded capture on Android 15+.
            val root = window.getRoot(0)
            state.rootAcquireMaxMs = maxOf(state.rootAcquireMaxMs, elapsedMs() - rootStartedAtMs)
            if (root == null || !state.canQuery()) continue
            if (firstPackage == null) firstPackage = root.packageName?.toString()
            traverse(root, null, 0, null, nodes, state)
        }

        return CaptureResult(
            UiSnapshot(packageHint ?: firstPackage, screenWidth, screenHeight, elapsedMs(), windows.size, nodes),
            WindowToken.of(windows.map { it.id }),
            CaptureMetrics(state.windowsTraversed, nodes.size, state.maxDepth, state.rootAcquireMaxMs, state.childQueryMaxMs, state.budgetReason != null, state.budgetReason),
        )
    }

    /**
     * M2.3 late-ad probe: capture a single live root (e.g. the active window
     * root). The service's `windows` cache is refreshed by WINDOWS_CHANGED
     * events and can lag a splash ad window that appears without such an
     * event (observed on QQ Music: probe frames saw 3 windows, none with the
     * skip node, while uiautomator saw it). Reading the live active root
     * closes that gap. Returns null when the root is unavailable.
     */
    fun captureRoot(
        root: AccessibilityNodeInfo,
        packageHint: String?,
        screenWidth: Int,
        screenHeight: Int,
    ): CaptureResult {
        val startedAtMs = elapsedMs()
        val state = CaptureState(startedAtMs)
        val nodes = mutableListOf<UiNodeSnapshot>()
        val firstPackage = root.packageName?.toString()
        traverse(root, null, 0, null, nodes, state)
        return CaptureResult(
            UiSnapshot(packageHint ?: firstPackage, screenWidth, screenHeight, elapsedMs(), 1, nodes),
            WindowToken.of(emptyList()),
            CaptureMetrics(state.windowsTraversed, nodes.size, state.maxDepth, state.rootAcquireMaxMs, state.childQueryMaxMs, state.budgetReason != null, state.budgetReason),
        )
    }

    private fun traverse(node: AccessibilityNodeInfo, parentIndex: Int?, depth: Int, clickableAncestor: IntRect?, output: MutableList<UiNodeSnapshot>, state: CaptureState) {
        if (!state.canQuery()) return
        if (output.size >= MAX_NODES) { state.hit("NODE_LIMIT"); return }
        if (depth > MAX_DEPTH) { state.hit("DEPTH_LIMIT"); return }
        state.maxDepth = maxOf(state.maxDepth, depth)
        val rect = Rect().also(node::getBoundsInScreen).toIntRect()
        val currentIndex = output.size
        output += UiNodeSnapshot(currentIndex, parentIndex, node.viewIdResourceName, node.text?.toString(), node.contentDescription?.toString(), node.className?.toString(), node.packageName?.toString(), rect, node.isClickable, node.isEnabled, node.isVisibleToUser, depth, clickableAncestor)
        val nextAncestor = if (node.isClickable) rect else clickableAncestor
        for (i in 0 until node.childCount) {
            if (!state.canQuery() || output.size >= MAX_NODES) {
                if (output.size >= MAX_NODES) state.hit("NODE_LIMIT")
                break
            }
            val childStartedAtMs = elapsedMs()
            val child = node.getChild(i, 0)
            state.childQueryMaxMs = maxOf(state.childQueryMaxMs, elapsedMs() - childStartedAtMs)
            if (child != null) traverse(child, currentIndex, depth + 1, nextAncestor, output, state)
        }
    }

    private inner class CaptureState(private val startedAtMs: Long) {
        var windowsTraversed = 0
        var maxDepth = 0
        var rootAcquireMaxMs = 0L
        var childQueryMaxMs = 0L
        var budgetReason: String? = null
        fun canQuery(): Boolean {
            if (budgetReason != null) return false
            if (elapsedMs() - startedAtMs < CAPTURE_DEADLINE_MS) return true
            hit("TIME_LIMIT")
            return false
        }
        fun hit(reason: String) { if (budgetReason == null) budgetReason = reason }
    }

    private fun Rect.toIntRect() = IntRect(left, top, right, bottom)

    private companion object {
        const val MAX_NODES = 2_000
        const val MAX_DEPTH = 80
        const val CAPTURE_DEADLINE_MS = 75L
    }
}
