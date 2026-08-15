package app.bypassads.core.actuation

import app.bypassads.core.model.UiNodeSnapshot

/**
 * Result of re-resolving the target against a freshly acquired window tree,
 * immediately before actuation (ADR-0002 §4).
 *
 * The caller (a future actuator) is responsible for producing this from the
 * latest tree; the gate only consumes it. `matches` holds the nodes whose
 * label/contentDescription equals the proposal label. Zero matches and more
 * than one match must both block.
 */
data class FreshTargetResolution(
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
) {
    val matchCount: Int get() = matches.size
    val uniqueMatch: UiNodeSnapshot? get() = matches.singleOrNull()
}
