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
            if (!state.canContinue()) break
            state.windowsTraversed++
            val rootStartedAtMs = elapsedMs()
            val root = window.root
            state.rootAcquireMaxMs = maxOf(state.rootAcquireMaxMs, elapsedMs() - rootStartedAtMs)
            if (root == null || !state.canContinue()) continue
            if (firstPackage == null) firstPackage = root.packageName?.toString()
            traverse(root, null, 0, null, nodes, state)
        }

        return CaptureResult(
            UiSnapshot(packageHint ?: firstPackage, screenWidth, screenHeight, elapsedMs(), windows.size, nodes),
            CaptureMetrics(state.windowsTraversed, nodes.size, state.maxDepth, state.rootAcquireMaxMs, state.childQueryMaxMs, state.budgetReason != null, state.budgetReason),
        )
    }

    private fun traverse(node: AccessibilityNodeInfo, parentIndex: Int?, depth: Int, clickableAncestor: IntRect?, output: MutableList<UiNodeSnapshot>, state: CaptureState) {
        if (!state.canContinue()) return
        if (output.size >= MAX_NODES) { state.budgetReason = "NODE_LIMIT"; return }
        if (depth > MAX_DEPTH) { state.budgetReason = "DEPTH_LIMIT"; return }
        state.maxDepth = maxOf(state.maxDepth, depth)
        val rect = Rect().also(node::getBoundsInScreen).toIntRect()
        val currentIndex = output.size
        output += UiNodeSnapshot(currentIndex, parentIndex, node.viewIdResourceName, node.text?.toString(), node.contentDescription?.toString(), node.className?.toString(), node.packageName?.toString(), rect, node.isClickable, node.isEnabled, node.isVisibleToUser, depth, clickableAncestor)
        val nextAncestor = if (node.isClickable) rect else clickableAncestor
        for (i in 0 until node.childCount) {
            if (!state.canContinue()) break
            val childStartedAtMs = elapsedMs()
            val child = node.getChild(i)
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
        fun canContinue(): Boolean {
            if (elapsedMs() - startedAtMs < CAPTURE_DEADLINE_MS) return true
            budgetReason = "TIME_LIMIT"
            return false
        }
    }

    private fun Rect.toIntRect() = IntRect(left, top, right, bottom)

    private companion object {
        const val MAX_NODES = 2_000
        const val MAX_DEPTH = 80
        const val CAPTURE_DEADLINE_MS = 75L
    }
}
