package app.bypassads.core.actuation

/**
 * Immediate post-dispatch outcome classification (M2.2 outcome-telemetry fix,
 * planner-approved P1).
 *
 * On HyperOS the `AccessibilityNodeInfo.performAction(ACTION_CLICK)` boolean
 * is not a reliable proof of the final UI effect: it can report false while
 * the click still takes effect (observed on the target device). Judging
 * `NO_EFFECT` from that boolean alone pollutes outcome telemetry, so the
 * immediate value after a single dispatched click is always `UNCERTAIN`;
 * the final outcome is decided by the passive post-action verification
 * ([ActuationOutcomeClassifier]) on later observations — never by a second
 * click.
 */
fun dispatchOutcome(matchCount: Int): ActuationOutcome = when {
    // 0 live matches: no click was even attempted (target/window not found).
    matchCount == 0 -> ActuationOutcome.NO_EFFECT
    // 1 match: one ACTION_CLICK was dispatched; effect unknown until verified.
    // >1 match: ambiguous — no click, and outcome cannot be claimed either way.
    else -> ActuationOutcome.UNCERTAIN
}
