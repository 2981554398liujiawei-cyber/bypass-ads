package app.bypassads.core.actuation

import app.bypassads.core.model.UiNodeSnapshot

/**
 * Result of re-resolving the target against a freshly acquired window tree,
 * immediately before actuation (ADR-0002 §4).
 *
 * Construction is `internal` on purpose (M2.1 hard gate): the only way to
 * obtain an instance from outside this module is through
 * [FreshTargetResolver] — callers can never hand-craft a "looks safe"
 * resolution, and (being a plain final class) there is no public `copy()`
 * escape hatch either. `matches` holds the nodes whose label/contentDescription
 * equals the proposal label. Zero matches and more than one match must both
 * block.
 */
class FreshTargetResolution internal constructor(
    val packageName: String?,
    /** Current monotonic generation; the gate compares it to the proposal's. */
    val currentCaseGeneration: Long,
    /** Current window identity token; the gate compares it to the proposal's. */
    val currentWindowContextToken: Long,
    /** Capture time of this fresh snapshot; must be newer than the proposal. */
    val freshCapturedAtElapsedMs: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val matches: List<UiNodeSnapshot>,
    val ctaSiblingDetected: Boolean = false,
    val countdownAnomaly: Boolean = false,
    /**
     * Mirrors the proposal's Fast Path flag (M2.3): the actuator uses it to
     * allow a live lookup on a non-clickable approved node. Set by the
     * coordinator from the proposal; the resolver itself never decides it.
     */
    val fastPath: Boolean = false,
) {
    val matchCount: Int get() = matches.size
    val uniqueMatch: UiNodeSnapshot? get() = matches.singleOrNull()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FreshTargetResolution) return false
        return packageName == other.packageName &&
            currentCaseGeneration == other.currentCaseGeneration &&
            currentWindowContextToken == other.currentWindowContextToken &&
            freshCapturedAtElapsedMs == other.freshCapturedAtElapsedMs &&
            screenWidth == other.screenWidth &&
            screenHeight == other.screenHeight &&
            matches == other.matches &&
            ctaSiblingDetected == other.ctaSiblingDetected &&
            countdownAnomaly == other.countdownAnomaly &&
            fastPath == other.fastPath
    }

    override fun hashCode(): Int {
        var result = packageName?.hashCode() ?: 0
        result = 31 * result + currentCaseGeneration.hashCode()
        result = 31 * result + currentWindowContextToken.hashCode()
        result = 31 * result + freshCapturedAtElapsedMs.hashCode()
        result = 31 * result + screenWidth
        result = 31 * result + screenHeight
        result = 31 * result + matches.hashCode()
        result = 31 * result + ctaSiblingDetected.hashCode()
        result = 31 * result + countdownAnomaly.hashCode()
        result = 31 * result + fastPath.hashCode()
        return result
    }
}
