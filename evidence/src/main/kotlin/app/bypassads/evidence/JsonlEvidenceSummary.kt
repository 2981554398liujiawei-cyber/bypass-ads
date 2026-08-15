package app.bypassads.evidence

import org.json.JSONObject

/** Count-only metrics for a local trace. Missing and invalid values are never interpreted as zero. */
data class JsonlEvidenceSummary(
    val postCutoffRecords: Int,
    val distinctCases: Int,
    val scanRecords: Int,
    val terminalRecords: Int,
    val durationMs: Long,
    val terminationReasons: Map<String, Int>,
    val timings: Map<String, MetricSummary>,
    val captureBudget: BooleanMetricSummary,
    val workerBusyDrops: CounterSummary,
    val workerMaxQueueDepth: MetricSummary,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("postCutoffRecords", postCutoffRecords)
        .put("distinctCases", distinctCases)
        .put("scanRecords", scanRecords)
        .put("terminalRecords", terminalRecords)
        .put("durationMs", durationMs)
        .put("terminationReasons", JSONObject(terminationReasons))
        .put("timings", JSONObject(timings.mapValues { it.value.toJson() }))
        .put("captureBudget", captureBudget.toJson())
        .put("workerBusyDrops", workerBusyDrops.toJson())
        .put("workerMaxQueueDepth", workerMaxQueueDepth.toJson())
}

data class MetricSummary(
    val expectedSamples: Int,
    val observedSamples: Int,
    val missingSamples: Int,
    val invalidSamples: Int,
    val p50Ms: Long?,
    val p95Ms: Long?,
    val maxMs: Long?,
    val slowAtOrAboveOneSecond: Int,
) {
    val complete: Boolean get() = expectedSamples == observedSamples && missingSamples == 0 && invalidSamples == 0

    fun toJson(): JSONObject = JSONObject()
        .put("expectedSamples", expectedSamples)
        .put("observedSamples", observedSamples)
        .put("missingSamples", missingSamples)
        .put("invalidSamples", invalidSamples)
        .put("complete", complete)
        .put("p50Ms", p50Ms)
        .put("p95Ms", p95Ms)
        .put("maxMs", maxMs)
        .put("slowAtOrAboveOneSecond", slowAtOrAboveOneSecond)
}

data class BooleanMetricSummary(
    val expectedSamples: Int,
    val trueSamples: Int,
    val falseSamples: Int,
    val missingSamples: Int,
    val invalidSamples: Int,
) {
    val observedSamples: Int get() = trueSamples + falseSamples
    val complete: Boolean get() = expectedSamples == observedSamples && missingSamples == 0 && invalidSamples == 0

    fun toJson(): JSONObject = JSONObject()
        .put("expectedSamples", expectedSamples)
        .put("observedSamples", observedSamples)
        .put("trueSamples", trueSamples)
        .put("falseSamples", falseSamples)
        .put("missingSamples", missingSamples)
        .put("invalidSamples", invalidSamples)
        .put("complete", complete)
}

data class CounterSummary(
    val expectedSamples: Int,
    val observedSamples: Int,
    val missingSamples: Int,
    val invalidSamples: Int,
    val first: Long?,
    val last: Long?,
    val max: Long?,
    val resetCount: Int,
    val increaseCount: Int,
) {
    val complete: Boolean get() = expectedSamples == observedSamples && missingSamples == 0 && invalidSamples == 0

    fun toJson(): JSONObject = JSONObject()
        .put("expectedSamples", expectedSamples)
        .put("observedSamples", observedSamples)
        .put("missingSamples", missingSamples)
        .put("invalidSamples", invalidSamples)
        .put("complete", complete)
        .put("first", first)
        .put("last", last)
        .put("max", max)
        .put("resetCount", resetCount)
        .put("increaseCount", increaseCount)
}

internal class JsonlEvidenceSummaryCollector {
    private val caseIds = mutableSetOf<String>()
    private val terminationReasons = mutableMapOf<String, Int>()
    private val timingValues = TIMING_FIELDS.associateWith { mutableListOf<Long>() }
    private val timingMissing = TIMING_FIELDS.associateWith { 0 }.toMutableMap()
    private val timingInvalid = TIMING_FIELDS.associateWith { 0 }.toMutableMap()
    private var firstEpochMs: Long? = null
    private var lastEpochMs: Long? = null
    private var postCutoffRecords = 0
    private var scanRecords = 0
    private var terminalRecords = 0
    private var captureBudgetTrue = 0
    private var captureBudgetFalse = 0
    private var captureBudgetMissing = 0
    private var captureBudgetInvalid = 0
    private val workerBusyDrops = CounterCollector()
    private val workerQueueDepth = MetricCollector()

    fun recordSeen(epochMs: Long) {
        postCutoffRecords++
        firstEpochMs = minOf(firstEpochMs ?: epochMs, epochMs)
        lastEpochMs = maxOf(lastEpochMs ?: epochMs, epochMs)
    }

    fun recordKnownTrigger(json: JSONObject, trigger: String, caseId: String?) {
        caseId?.let(caseIds::add)
        if (trigger == "CASE_END") {
            terminalRecords++
            json.strictString("terminationReason")?.let { reason ->
                terminationReasons[reason] = (terminationReasons[reason] ?: 0) + 1
            }
        }
        if (trigger != "SCAN") return

        scanRecords++
        TIMING_FIELDS.forEach { field ->
            when (val value = json.strictLong(field)) {
                MetricValue.Missing -> timingMissing[field] = timingMissing.getValue(field) + 1
                MetricValue.Invalid -> timingInvalid[field] = timingInvalid.getValue(field) + 1
                is MetricValue.Value -> timingValues.getValue(field).add(value.value)
            }
        }
        when (val value = json.strictBoolean("captureBudgetHit")) {
            MetricValue.Missing -> captureBudgetMissing++
            MetricValue.Invalid -> captureBudgetInvalid++
            is MetricValue.Value -> if (value.value) captureBudgetTrue++ else captureBudgetFalse++
        }
        workerBusyDrops.record(json.strictLong("workerBusyDrops"))
        workerQueueDepth.record(json.strictLong("workerMaxQueueDepth"))
    }

    fun summary(): JsonlEvidenceSummary = JsonlEvidenceSummary(
        postCutoffRecords = postCutoffRecords,
        distinctCases = caseIds.size,
        scanRecords = scanRecords,
        terminalRecords = terminalRecords,
        durationMs = ((lastEpochMs ?: 0L) - (firstEpochMs ?: 0L)).coerceAtLeast(0L),
        terminationReasons = terminationReasons.toSortedMap(),
        timings = timingValues.mapValues { (field, values) -> values.toMetricSummary(scanRecords, timingMissing.getValue(field), timingInvalid.getValue(field)) },
        captureBudget = BooleanMetricSummary(scanRecords, captureBudgetTrue, captureBudgetFalse, captureBudgetMissing, captureBudgetInvalid),
        workerBusyDrops = workerBusyDrops.summary(scanRecords),
        workerMaxQueueDepth = workerQueueDepth.summary(scanRecords),
    )

    private fun List<Long>.toMetricSummary(expected: Int, missing: Int, invalid: Int): MetricSummary {
        if (isEmpty()) return MetricSummary(expected, 0, missing, invalid, null, null, null, 0)
        val sorted = sorted()
        fun percentile(percent: Int): Long = sorted[((sorted.size * percent + 99) / 100) - 1]
        return MetricSummary(expected, size, missing, invalid, percentile(50), percentile(95), sorted.last(), count { it >= ONE_SECOND_MS })
    }

    private inner class MetricCollector {
        private val values = mutableListOf<Long>()
        private var missing = 0
        private var invalid = 0

        fun record(value: MetricValue<Long>) = when (value) {
            MetricValue.Missing -> missing++
            MetricValue.Invalid -> invalid++
            is MetricValue.Value -> values += value.value
        }

        fun summary(expected: Int): MetricSummary = values.toMetricSummary(expected, missing, invalid)
    }

    private class CounterCollector {
        private val values = mutableListOf<Long>()
        private var missing = 0
        private var invalid = 0

        fun record(value: MetricValue<Long>) = when (value) {
            MetricValue.Missing -> missing++
            MetricValue.Invalid -> invalid++
            is MetricValue.Value -> values += value.value
        }

        fun summary(expected: Int): CounterSummary {
            val resets = values.zipWithNext().count { (previous, next) -> next < previous }
            val increases = values.zipWithNext().count { (previous, next) -> next > previous }
            return CounterSummary(expected, values.size, missing, invalid, values.firstOrNull(), values.lastOrNull(), values.maxOrNull(), resets, increases)
        }
    }

    private sealed interface MetricValue<out T> {
        data object Missing : MetricValue<Nothing>
        data object Invalid : MetricValue<Nothing>
        data class Value<T>(val value: T) : MetricValue<T>
    }

    private fun JSONObject.strictLong(key: String): MetricValue<Long> {
        if (!has(key) || isNull(key)) return MetricValue.Missing
        val value = get(key)
        if (value !is Number) return MetricValue.Invalid
        val asLong = value.toLong()
        return if (value.toDouble() == asLong.toDouble() && asLong >= 0) MetricValue.Value(asLong) else MetricValue.Invalid
    }

    private fun JSONObject.strictBoolean(key: String): MetricValue<Boolean> {
        if (!has(key) || isNull(key)) return MetricValue.Missing
        return (get(key) as? Boolean)?.let { MetricValue.Value(it) } ?: MetricValue.Invalid
    }

    private fun JSONObject.strictString(key: String): String? = if (!has(key) || isNull(key)) null else (get(key) as? String)?.takeIf(String::isNotBlank)

    private companion object {
        const val ONE_SECOND_MS = 1_000L
        val TIMING_FIELDS = listOf("scanWorkMs", "windowAcquireMs", "snapshotMs", "detectionMs", "decisionMs", "rootAcquireMaxMs", "childQueryMaxMs")
    }
}
