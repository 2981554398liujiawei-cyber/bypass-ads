package app.lttnext.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.lttnext.core.model.IntRect
import app.lttnext.core.model.UiNodeSnapshot
import app.lttnext.core.model.UiSnapshot

class NodeSnapshotBuilder {
    fun capture(
        windows: List<AccessibilityWindowInfo>,
        packageHint: String?,
        screenWidth: Int,
        screenHeight: Int,
        elapsedMs: Long,
    ): UiSnapshot {
        val nodes = mutableListOf<UiNodeSnapshot>()
        var firstPackage: String? = null

        windows.forEach { window ->
            val root = window.root ?: return@forEach
            if (firstPackage == null) firstPackage = root.packageName?.toString()
            traverse(root, parentIndex = null, depth = 0, clickableAncestor = null, output = nodes)
        }

        return UiSnapshot(
            packageName = packageHint ?: firstPackage,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            capturedAtElapsedMs = elapsedMs,
            windowCount = windows.size,
            nodes = nodes,
        )
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        parentIndex: Int?,
        depth: Int,
        clickableAncestor: IntRect?,
        output: MutableList<UiNodeSnapshot>,
    ) {
        if (output.size >= MAX_NODES || depth > MAX_DEPTH) return
        val rect = Rect().also(node::getBoundsInScreen).toIntRect()
        val currentIndex = output.size
        output += UiNodeSnapshot(
            index = currentIndex,
            parentIndex = parentIndex,
            resourceId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            bounds = rect,
            clickable = node.isClickable,
            enabled = node.isEnabled,
            visibleToUser = node.isVisibleToUser,
            depth = depth,
            nearestClickableAncestorBounds = clickableAncestor,
        )

        val nextAncestor = if (node.isClickable) rect else clickableAncestor
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, currentIndex, depth + 1, nextAncestor, output)
            if (output.size >= MAX_NODES) break
        }
    }

    private fun Rect.toIntRect() = IntRect(left, top, right, bottom)

    private companion object {
        const val MAX_NODES = 2_000
        const val MAX_DEPTH = 80
    }
}
