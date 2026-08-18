package app.bypassads.actuation

import android.view.accessibility.AccessibilityNodeInfo
import app.bypassads.core.actuation.ActuationDispatchTracker
import app.bypassads.core.actuation.ActuationOutcome
import app.bypassads.core.actuation.ActuationVerdict
import app.bypassads.core.actuation.FreshTargetResolution
import app.bypassads.core.actuation.NodeActuator
import app.bypassads.core.actuation.dispatchOutcome
import app.bypassads.core.model.IntRect
import app.bypassads.core.model.LabelSanitizer

/**
 * Identity the gate approved, re-verified at execution time (M2.2 P1-5):
 * package + sanitized explicit label + resourceId (when the proposal had one)
 * + bounds/geometry. The live lookup must match this exactly and uniquely
 * before a single click; there is no coordinate-guessing fallback.
 */
data class ApprovedTarget(
    val packageName: String?,
    val label: String,
    val resourceId: String?,
    val bounds: IntRect,
    /** Non-null for M2.3 Real-App Fast Path targets: the exact rule id that
     *  statically approved package+label+splash scope+region. The live lookup
     *  matches the (possibly non-clickable) label anchor and resolves a
     *  verified clickable ancestor for the actual click — the click never
     *  lands on the non-clickable anchor node itself. */
    val fastPathRuleId: String? = null,
)

/**
 * M2.1/M2.2 experiment: Android adapter that performs a single
 * [AccessibilityNodeInfo.performAction] ACTION_CLICK — and nothing else.
 *
 * Safety rules (ADR-0002, M2.1 task 3, M2.2 P1-5):
 * - Only reachable with an [ActuationVerdict.Allow] (enforced by the
 *   [NodeActuator] interface type). Any other verdict is inexpressible.
 * - Exactly one call attempt per case; a failed action is never re-clicked.
 * - The live node is re-looked-up at execution time against the approved
 *   identity (package / label / resourceId / bounds). 0 matches -> NO_EFFECT;
 *   more than 1 match -> UNCERTAIN and no click; exactly 1 -> click.
 * - Outcome honesty (M2.2 P1, planner-approved): the `performAction` boolean
 *   is unreliable on HyperOS (observed false while the click still takes
 *   effect), so a single dispatched click is always reported UNCERTAIN
 *   immediately; the final SUCCESS / NO_EFFECT is decided later by the
 *   passive post-action verification, never by a second click.
 * - This class decides nothing: no scoring, no policy, no fallback.
 */
class AndroidNodeActuator(
    private val nodeLookup: (ApprovedTarget) -> List<AccessibilityNodeInfo>,
    /**
     * M2.3 WebView compatibility backend: a gesture click at the live node's
     * bounds center, provided by the AccessibilityService (dispatchGesture).
     * Used only for Fast Path targets — observed on QQ Music that a WebView
     * composite node's ACTION_CLICK is dispatched (performAction=true) but has
     * no effect (OUTCOME_VERIFIED=NO_EFFECT), while a real coordinate gesture
     * on the same node works. The coordinates are always re-derived from the
     * freshly resolved node bounds (never fixed), and the bounds have already
     * passed the full gate pipeline (region/area/geometry). Returns:
     * true = gesture completed, false = cancelled, null = unsupported/timeout.
     */
    private val gestureClick: ((IntRect) -> Boolean?)? = null,
    /**
     * M2.3 (planner-narrowed): only this Fast Path rule id may use the gesture
     * backend — QQ Music's WebView splash ads do not respond to node
     * ACTION_CLICK. All other Fast Path targets keep performAction.
     */
    private val gestureRuleId: String? = null,
) : NodeActuator {

    /**
     * Per-attempt dispatch state (M2.2 P1): null = no click dispatched this
     * attempt (0/>1 matches or performAction never returned); true/false =
     * ACTION_CLICK was actually invoked (raw performAction boolean, kept as
     * diagnostic only — never used for the outcome).
     */
    val dispatchTracker = ActuationDispatchTracker()

    override fun actuate(
        verdict: ActuationVerdict.Allow,
        resolution: FreshTargetResolution,
    ): ActuationOutcome {
        dispatchTracker.beginAttempt()
        val target = resolution.uniqueMatch ?: return ActuationOutcome.NO_EFFECT
        val approved = ApprovedTarget(
            packageName = target.packageName,
            label = approvedLabel(target),
            resourceId = target.resourceId,
            bounds = target.bounds,
            fastPathRuleId = resolution.fastPathRuleId,
        )
        val matches = nodeLookup(approved)
        if (matches.size != 1) return dispatchOutcome(matches.size) // 0 -> NO_EFFECT (no click); >1 -> UNCERTAIN (no click); tracker stays null

        val dispatched = if (resolution.fastPathRuleId == gestureRuleId && gestureClick != null) {
            // M2.3: QQ Music's WebView composite node ignores ACTION_CLICK.
            // Gesture at the freshly resolved live bounds center instead.
            gestureClick(target.bounds)
        } else {
            matches[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        dispatchTracker.recordDispatch(dispatched == true)
        // performAction=true only proves the click was dispatched; the boolean
        // is not reliable on HyperOS, and confirmation (target/window gone)
        // happens via the passive verifier on a later observation. The honest
        // immediate value is therefore always UNCERTAIN for a dispatched click.
        return ActuationOutcome.UNCERTAIN
    }

    private fun approvedLabel(target: app.bypassads.core.model.UiNodeSnapshot): String {
        val candidates = listOfNotNull(target.text, target.contentDescription)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(LabelSanitizer::sanitizeLabel)
        return candidates.firstOrNull { it != "matched" } ?: target.text?.trim() ?: ""
    }
}
