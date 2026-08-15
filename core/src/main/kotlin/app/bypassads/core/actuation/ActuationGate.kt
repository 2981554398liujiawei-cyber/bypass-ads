package app.bypassads.core.actuation

import app.bypassads.core.actuation.ActuationVerdict.Allow
import app.bypassads.core.actuation.ActuationVerdict.Block
import app.bypassads.core.model.WeightedReason

/**
 * Fail-closed actuation gate (ADR-0002 §3).
 *
 * Pure Kotlin: no Android API. Detection/scoring and actuation authorization
 * are two independent safety boundaries; this gate never inherits scorer
 * trust. Every condition must hold for [Allow]; any failure returns a
 * [Block] with a concrete reason.
 *
 * Hardened invariants (M2.0.1):
 * - the gate itself compares proposal vs current generation and window token
 *   (it cannot be told "fresh enough" by the caller);
 * - the fresh snapshot must be strictly newer than the proposal;
 * - at most one actuation attempt per case is enforced here, not just
 *   declared;
 * - the active allowed zone is top-left/top-right only.
 */
class ActuationGate(
    private val clickThreshold: Int = 80,
    private val riskFloor: Int = -50,
    private val maxSmallAreaRatio: Double = 0.06,
    private val maxDriftRatio: Double = 0.05,
    private val largeAncestorAreaRatio: Double = 0.20,
) {

    fun evaluate(
        proposal: ActionProposal,
        modeAllowsAction: Boolean,
        caseActive: Boolean,
        attemptsAlreadyMade: Int,
        fresh: FreshTargetResolution,
        revalidatedScore: Int,
        revalidatedRisks: List<WeightedReason>,
    ): ActuationVerdict {
        if (!modeAllowsAction) return Block(ActuationBlockReason.MODE_BLOCKED)
        if (!caseActive) return Block(ActuationBlockReason.CASE_STALE)
        if (attemptsAlreadyMade >= MAX_ACTUATION_ATTEMPTS_PER_CASE) {
            return Block(ActuationBlockReason.ATTEMPT_LIMIT_REACHED)
        }

        // Proposal/current context must be bound to the same case, window, and time.
        if (proposal.caseGeneration != fresh.currentCaseGeneration) {
            return Block(ActuationBlockReason.STALE_GENERATION)
        }
        if (proposal.windowContextToken != fresh.currentWindowContextToken) {
            return Block(ActuationBlockReason.WINDOW_CHANGED)
        }
        if (fresh.freshCapturedAtElapsedMs <= proposal.proposedAtElapsedMs) {
            return Block(ActuationBlockReason.REVALIDATION_STALE)
        }
        if (fresh.packageName != proposal.packageName) return Block(ActuationBlockReason.PACKAGE_CHANGED)

        // Uniqueness of the re-resolved target.
        if (fresh.matchCount == 0) return Block(ActuationBlockReason.TARGET_DISAPPEARED)
        if (fresh.matchCount > 1) return Block(ActuationBlockReason.MULTIPLE_TARGETS)
        val target = fresh.uniqueMatch ?: return Block(ActuationBlockReason.TARGET_DISAPPEARED)

        // Explicit label must still be present (fresh.matches are label matches,
        // so this is effectively guaranteed; kept explicit per contract).
        val fp = proposal.fingerprint
        if (!fresh.matches.any { it.text == fp.label || it.contentDescription == fp.label }) {
            return Block(ActuationBlockReason.LABEL_CHANGED)
        }

        // resourceId strengthens identity when present.
        fp.resourceId?.let { rid ->
            if (target.resourceId != rid) return Block(ActuationBlockReason.IDENTITY_AMBIGUOUS)
        }

        // Geometry drift (normalized center distance).
        if (fp.normalizedDistanceTo(target.bounds) > maxDriftRatio) {
            return Block(ActuationBlockReason.GEOMETRY_CHANGED)
        }

        val areaRatio = target.bounds.areaRatio(fresh.screenWidth, fresh.screenHeight)
        if (areaRatio > maxSmallAreaRatio) return Block(ActuationBlockReason.LARGE_TARGET)

        // Active allowed zone: top-left or top-right only (ADR-0002 §3, P1-3).
        val cx = target.bounds.centerX.toDouble() / fresh.screenWidth.coerceAtLeast(1)
        val cy = target.bounds.centerY.toDouble() / fresh.screenHeight.coerceAtLeast(1)
        val inTopRight = cx >= 0.70 && cy <= 0.25
        val inTopLeft = cx <= 0.30 && cy <= 0.25
        if (!inTopRight && !inTopLeft) {
            return Block(ActuationBlockReason.GEOMETRY_CHANGED)
        }

        if (!target.clickable || !target.enabled || !target.visibleToUser) {
            return Block(ActuationBlockReason.NOT_CLICKABLE)
        }

        target.nearestClickableAncestorBounds?.let { parent ->
            if (parent.areaRatio(fresh.screenWidth, fresh.screenHeight) >= largeAncestorAreaRatio) {
                return Block(ActuationBlockReason.ANCESTOR_RISK)
            }
        }

        if (fresh.ctaSiblingDetected) return Block(ActuationBlockReason.CTA_RISK)
        if (fresh.countdownAnomaly) return Block(ActuationBlockReason.COUNTDOWN_ANOMALY)

        if (revalidatedScore < clickThreshold) return Block(ActuationBlockReason.SCORE_DROPPED)
        if (revalidatedRisks.any { it.points <= riskFloor }) return Block(ActuationBlockReason.RISK_BLOCKED)

        return Allow(proposal.fingerprint)
    }
}
