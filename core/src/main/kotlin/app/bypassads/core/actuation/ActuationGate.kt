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
        generationStale: Boolean,
        fresh: FreshTargetResolution,
        revalidatedScore: Int,
        revalidatedRisks: List<WeightedReason>,
    ): ActuationVerdict {
        if (!modeAllowsAction) return Block(ActuationBlockReason.MODE_BLOCKED)
        if (!caseActive) return Block(ActuationBlockReason.CASE_STALE)
        if (generationStale) return Block(ActuationBlockReason.STALE_GENERATION)
        if (fresh.packageName != proposal.packageName) return Block(ActuationBlockReason.PACKAGE_CHANGED)
        if (fresh.windowCount != proposal.windowCountAtProposal) return Block(ActuationBlockReason.WINDOW_CHANGED)

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

        // Central position is outside the allowed zone.
        val cx = target.bounds.centerX.toDouble() / fresh.screenWidth.coerceAtLeast(1)
        val cy = target.bounds.centerY.toDouble() / fresh.screenHeight.coerceAtLeast(1)
        if (cx in 0.30..0.70 && cy in 0.25..0.75) {
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
