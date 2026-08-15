package app.bypassads.core.detection

import app.bypassads.core.model.CandidateFeatures
import kotlin.math.abs

/**
 * Enriches privacy-safe candidate features within a single burst. No raw UI text is retained.
 */
class TemporalCandidateTracker {
    private val observations = mutableMapOf<String, List<Observation>>()

    fun reset() {
        observations.clear()
    }

    fun enrich(features: List<CandidateFeatures>): List<CandidateFeatures> = features
        .groupBy(::identity)
        .flatMap { (key, currentGroup) ->
            val previousGroup = observations[key].orEmpty()
            // Multiple visually similar candidates are an ambiguity, not proof that one candidate jumped.
            val ambiguousGroup = currentGroup.size > 1 || previousGroup.size > 1
            val previous = previousGroup.singleOrNull()
            val enriched = currentGroup.map { current ->
                val positionDrifted = !ambiguousGroup && previous != null && movedSignificantly(current, previous)
                val hits = if (previous == null || ambiguousGroup || positionDrifted) 1 else previous.hits + 1
                val countdownProgressed = !ambiguousGroup && !positionDrifted &&
                    previous?.countdownValue != null &&
                    current.countdownValue != null &&
                    current.countdownValue < previous.countdownValue &&
                    previous.countdownValue - current.countdownValue <= 2
                current.copy(
                    stabilityHits = hits,
                    countdownStepObserved = current.countdownStepObserved || countdownProgressed,
                    positionDriftDetected = current.positionDriftDetected || previous?.driftObserved == true || positionDrifted,
                )
            }
            observations[key] = enriched.map { candidate ->
                Observation(
                    hits = candidate.stabilityHits,
                    countdownValue = candidate.countdownValue,
                    centerX = candidate.bounds.centerX,
                    centerY = candidate.bounds.centerY,
                    driftObserved = candidate.positionDriftDetected,
                )
            }
            enriched
        }

    private fun identity(value: CandidateFeatures): String = buildString {
        append(value.resourceId ?: value.label)
        append('|').append(value.screenWidth)
        append('|').append(value.screenHeight)
    }

    private fun movedSignificantly(current: CandidateFeatures, previous: Observation): Boolean {
        val horizontalDelta = abs(current.bounds.centerX - previous.centerX).toDouble() /
            current.screenWidth.coerceAtLeast(1)
        val verticalDelta = abs(current.bounds.centerY - previous.centerY).toDouble() /
            current.screenHeight.coerceAtLeast(1)
        return horizontalDelta > MAX_POSITION_DELTA_RATIO || verticalDelta > MAX_POSITION_DELTA_RATIO
    }

    private data class Observation(
        val hits: Int,
        val countdownValue: Int?,
        val centerX: Int,
        val centerY: Int,
        val driftObserved: Boolean,
    )

    private companion object {
        const val MAX_POSITION_DELTA_RATIO = 0.08
    }
}
