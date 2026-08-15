package app.bypassads.core.actuation

import app.bypassads.core.model.IntRect
import app.bypassads.core.model.SkipCandidate

/**
 * Identity fingerprint of the target that a future actuator would click.
 *
 * Built once from the Shadow [SkipCandidate] at proposal time and re-checked
 * against the freshly re-resolved window tree. `resourceId` may strengthen
 * identity, but it must never authorize a click on its own (ADR-0002 §4).
 */
data class TargetFingerprint(
    val label: String,
    val packageName: String?,
    val resourceId: String?,
    val bounds: IntRect,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    val centerXRatio: Double get() = bounds.centerX.toDouble() / screenWidth.coerceAtLeast(1)
    val centerYRatio: Double get() = bounds.centerY.toDouble() / screenHeight.coerceAtLeast(1)
    val areaRatio: Double get() = bounds.areaRatio(screenWidth, screenHeight)

    fun normalizedDistanceTo(other: IntRect): Double {
        val dx = (bounds.centerX - other.centerX).toDouble() / screenWidth.coerceAtLeast(1)
        val dy = (bounds.centerY - other.centerY).toDouble() / screenHeight.coerceAtLeast(1)
        return kotlin.math.hypot(dx, dy)
    }
}

/** Sanitized label: trimmed; internal whitespace collapsed for matching. */
fun sanitizeLabel(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

fun buildTargetFingerprint(candidate: SkipCandidate, packageName: String?): TargetFingerprint =
    TargetFingerprint(
        label = sanitizeLabel(candidate.label),
        packageName = packageName,
        resourceId = candidate.resourceId,
        bounds = candidate.bounds,
        screenWidth = candidate.features.screenWidth,
        screenHeight = candidate.features.screenHeight,
    )
