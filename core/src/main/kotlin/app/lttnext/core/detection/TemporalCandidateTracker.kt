package app.lttnext.core.detection

import app.lttnext.core.model.CandidateFeatures

/**
 * Enriches privacy-safe candidate features within a single burst. No raw UI text is retained.
 */
class TemporalCandidateTracker {
    private val observations = mutableMapOf<String, Observation>()

    fun reset() {
        observations.clear()
    }

    fun enrich(features: List<CandidateFeatures>): List<CandidateFeatures> = features.map { current ->
        val key = fingerprint(current)
        val previous = observations[key]
        val hits = (previous?.hits ?: 0) + 1
        val countdownProgressed = previous?.countdownValue != null &&
            current.countdownValue != null &&
            current.countdownValue < previous.countdownValue &&
            previous.countdownValue - current.countdownValue <= 2

        observations[key] = Observation(hits = hits, countdownValue = current.countdownValue)
        current.copy(
            stabilityHits = hits,
            countdownStepObserved = current.countdownStepObserved || countdownProgressed,
        )
    }

    private fun fingerprint(value: CandidateFeatures): String {
        val b = value.bounds
        fun bucket(n: Int): Int = n / 24
        return buildString {
            append(value.resourceId ?: value.label)
            append('|').append(bucket(b.centerX))
            append('|').append(bucket(b.centerY))
            append('|').append(bucket(b.width))
            append('|').append(bucket(b.height))
        }
    }

    private data class Observation(
        val hits: Int,
        val countdownValue: Int?,
    )
}
