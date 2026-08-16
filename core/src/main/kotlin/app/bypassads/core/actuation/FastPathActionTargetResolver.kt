package app.bypassads.core.actuation

import app.bypassads.core.model.IntRect

/**
 * Action-target resolution for Real-App Fast Path anchors (M2.3 P1-1).
 *
 * The label anchor (e.g. Weibo "跳过" text node, clickable=false) only
 * *identifies* the skip; the actual ACTION_CLICK must land on a strictly
 * verified clickable node. Pure Kotlin so the safety decision is
 * unit-testable without Android APIs; the live Android adapter builds the
 * ancestor chain from real [android.view.accessibility.AccessibilityNodeInfo]
 * parents and maps the resolved index back to the live node.
 *
 * Safety contract (planner review):
 * - a clickable anchor (enabled + visible) is itself the action target;
 * - a non-clickable anchor requires a **unique** clickable ancestor that is
 *   same-package, enabled, visible, bounds-contains the anchor, and not
 *   excessively large;
 * - 0 or >1 qualifying ancestors -> null (BLOCK) — there is never a fallback
 *   to coordinate guessing or to clicking the non-clickable anchor itself.
 */
data class FastPathAncestorSnapshot(
    val packageName: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val bounds: IntRect,
)

object FastPathActionTargetResolver {

    /** True when the anchor itself is an acceptable action target. */
    fun anchorIsActionable(anchor: FastPathAncestorSnapshot): Boolean =
        anchor.clickable && anchor.enabled && anchor.visibleToUser

    /**
     * @return index into `ancestors` (nearest first) of the unique verified
     * clickable ancestor, or null when no safe action target exists (BLOCK).
     */
    fun resolveClickableAncestorIndex(
        anchor: FastPathAncestorSnapshot,
        ancestors: List<FastPathAncestorSnapshot>,
        screenWidth: Int,
        screenHeight: Int,
        maxAncestorAreaRatio: Double = MAX_ANCESTOR_AREA_RATIO,
    ): Int? {
        if (!anchor.enabled || !anchor.visibleToUser) return null
        val qualified = ancestors.mapIndexedNotNull { index, ancestor ->
            if (ancestor.clickable &&
                ancestor.enabled &&
                ancestor.visibleToUser &&
                ancestor.packageName == anchor.packageName &&
                contains(ancestor.bounds, anchor.bounds) &&
                ancestor.bounds.areaRatio(screenWidth, screenHeight) <= maxAncestorAreaRatio
            ) index else null
        }
        return qualified.singleOrNull()
    }

    private fun contains(outer: IntRect, inner: IntRect): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom

    private const val MAX_ANCESTOR_AREA_RATIO = 0.20
}
