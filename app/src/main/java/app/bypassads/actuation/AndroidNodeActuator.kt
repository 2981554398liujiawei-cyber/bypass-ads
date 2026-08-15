package app.bypassads.actuation

import android.view.accessibility.AccessibilityNodeInfo
import app.bypassads.core.actuation.ActuationOutcome
import app.bypassads.core.actuation.ActuationVerdict
import app.bypassads.core.actuation.FreshTargetResolution
import app.bypassads.core.actuation.NodeActuator
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
 * - This class decides nothing: no scoring, no policy, no fallback.
 *
 * NOT wired to any runtime path outside the experimental build's guarded
 * flow: release/debug policy keeps it unreachable.
 */
class AndroidNodeActuator(
    private val nodeLookup: (ApprovedTarget) -> List<AccessibilityNodeInfo>,
) : NodeActuator {

    override fun actuate(
        verdict: ActuationVerdict.Allow,
        resolution: FreshTargetResolution,
    ): ActuationOutcome {
        val target = resolution.uniqueMatch ?: return ActuationOutcome.NO_EFFECT
        val approved = ApprovedTarget(
            packageName = target.packageName,
            label = approvedLabel(target),
            resourceId = target.resourceId,
            bounds = target.bounds,
        )
        val matches = nodeLookup(approved)
        if (matches.isEmpty()) return ActuationOutcome.NO_EFFECT
        if (matches.size > 1) return ActuationOutcome.UNCERTAIN

        val ok = matches[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // performAction=true only proves the click was dispatched; outcome
        // confirmation (target/window gone) happens via the classifier on a
        // later observation, so the honest immediate value is UNCERTAIN.
        return if (ok) ActuationOutcome.UNCERTAIN else ActuationOutcome.NO_EFFECT
    }

    private fun approvedLabel(target: app.bypassads.core.model.UiNodeSnapshot): String {
        val candidates = listOfNotNull(target.text, target.contentDescription)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(LabelSanitizer::sanitizeLabel)
        return candidates.firstOrNull { it != "matched" } ?: target.text?.trim() ?: ""
    }
}
