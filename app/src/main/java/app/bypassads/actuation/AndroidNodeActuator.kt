package app.bypassads.actuation

import android.view.accessibility.AccessibilityNodeInfo
import app.bypassads.core.actuation.ActuationOutcome
import app.bypassads.core.actuation.ActuationVerdict
import app.bypassads.core.actuation.FreshTargetResolution
import app.bypassads.core.actuation.NodeActuator
import app.bypassads.core.model.IntRect

/**
 * M2.1 experiment: Android adapter that performs a single
 * [AccessibilityNodeInfo.performAction] ACTION_CLICK — and nothing else.
 *
 * Safety rules (ADR-0002, M2.1 task 3):
 * - Only reachable with an [ActuationVerdict.Allow] (enforced by the
 *   [NodeActuator] interface type). Any other verdict is inexpressible.
 * - Exactly one call attempt per case (`MAX_ACTUATION_ATTEMPTS_PER_CASE`);
 *   a failed action is never re-clicked (`NO_EFFECT`).
 * - The node is re-looked-up at execution time from the resolution bounds —
 *   never a stale cached handle.
 * - This class decides nothing: no scoring, no policy, no fallback.
 *
 * NOT wired to any runtime path in M2.1: there is no Active mode, no user
 * switch, no background execution. It exists so the safety chain
 * (Resolver -> Gate -> Allow -> Actuator -> ACTION_CLICK) is real and
 * testable, while no app behaviour can trigger it.
 */
class AndroidNodeActuator(
    private val nodeLookup: (bounds: IntRect) -> AccessibilityNodeInfo?,
) : NodeActuator {

    override fun actuate(
        verdict: ActuationVerdict.Allow,
        resolution: FreshTargetResolution,
    ): ActuationOutcome {
        val target = resolution.uniqueMatch ?: return ActuationOutcome.NO_EFFECT
        val node = nodeLookup(target.bounds) ?: return ActuationOutcome.NO_EFFECT
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // performAction=true only proves the click was dispatched; outcome
        // confirmation (target/window gone) happens via the classifier on a
        // later observation, so the honest immediate value is UNCERTAIN.
        return if (ok) ActuationOutcome.UNCERTAIN else ActuationOutcome.NO_EFFECT
    }
}
