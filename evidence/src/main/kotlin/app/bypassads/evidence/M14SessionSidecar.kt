package app.bypassads.evidence

import org.json.JSONArray
import org.json.JSONObject

/** Local-only operator assertions. Names stay in this input and are never emitted in the report. */
data class M14SessionSidecar(
    val collectionStartEpochMs: Long,
    val collectionEndEpochMs: Long?,
    val controlledRun: ControlledRun?,
    val continuousCollectionConfirmed: Boolean?,
    val serviceBoundAtEnd: Boolean?,
    val offCycles: List<OffCycle>,
    val finalIoHealth: IoHealth?,
    val coverage: Coverage?,
    val stability: Stability?,
    val slowReview: SlowReview?,
) {
    data class ControlledRun(val startEpochMs: Long, val endEpochMs: Long)
    data class OffCycle(val offAtEpochMs: Long, val shadowRestoredAtEpochMs: Long)
    data class IoHealth(val pendingIoJobs: Int, val maxPendingIoJobs: Int, val recordsWritten: Long, val writeFailures: Long)
    data class Coverage(val ordinaryAppCount: Int, val wechatMiniProgramNames: List<String>, val alipayMiniProgramNames: List<String>)
    data class Stability(val crashCount: Int, val anrCount: Int)
    data class SlowReview(val unexplainedSlowAccessibilityCount: Int, val repeatableMultiSecondBlockingCount: Int)

    companion object {
        fun parse(json: JSONObject): M14SessionSidecar = M14SessionSidecar(
            collectionStartEpochMs = json.requiredNonNegativeLong("collectionStartEpochMs"),
            collectionEndEpochMs = json.optionalNonNegativeLong("collectionEndEpochMs"),
            controlledRun = json.optionalObject("controlledRun")?.let { run -> ControlledRun(run.requiredNonNegativeLong("startEpochMs"), run.requiredNonNegativeLong("endEpochMs")) },
            continuousCollectionConfirmed = json.optionalBoolean("continuousCollectionConfirmed"),
            serviceBoundAtEnd = json.optionalBoolean("serviceBoundAtEnd"),
            offCycles = json.requiredArray("offCycles").mapObjects { item -> OffCycle(item.requiredNonNegativeLong("offAtEpochMs"), item.requiredNonNegativeLong("shadowRestoredAtEpochMs")) },
            finalIoHealth = json.optionalObject("finalIoHealth")?.let { health -> IoHealth(health.requiredNonNegativeInt("pendingIoJobs"), health.requiredNonNegativeInt("maxPendingIoJobs"), health.requiredNonNegativeLong("recordsWritten"), health.requiredNonNegativeLong("writeFailures")) },
            coverage = json.optionalObject("coverage")?.let { coverage -> Coverage(coverage.requiredNonNegativeInt("ordinaryAppCount"), coverage.requiredArray("wechatMiniProgramNames").mapStrings(), coverage.requiredArray("alipayMiniProgramNames").mapStrings()) },
            stability = json.optionalObject("stability")?.let { stability -> Stability(stability.requiredNonNegativeInt("crashCount"), stability.requiredNonNegativeInt("anrCount")) },
            slowReview = json.optionalObject("slowReview")?.let { review -> SlowReview(review.requiredNonNegativeInt("unexplainedSlowAccessibilityCount"), review.requiredNonNegativeInt("repeatableMultiSecondBlockingCount")) },
        )

        private fun JSONObject.requiredArray(key: String): JSONArray = getJSONArray(key)
        private fun JSONObject.optionalObject(key: String): JSONObject? = if (!has(key) || isNull(key)) null else getJSONObject(key)
        private fun JSONObject.optionalNonNegativeLong(key: String): Long? = if (!has(key) || isNull(key)) null else requiredNonNegativeLong(key)
        private fun JSONObject.optionalBoolean(key: String): Boolean? = if (!has(key) || isNull(key)) null else get(key) as? Boolean ?: error("$key must be a boolean")
        private fun JSONObject.requiredNonNegativeLong(key: String): Long = get(key).let { value -> (value as? Number)?.toLong()?.takeIf { it >= 0 && value.toDouble() == it.toDouble() } ?: error("$key must be a non-negative integer") }
        private fun JSONObject.requiredNonNegativeInt(key: String): Int = requiredNonNegativeLong(key).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: error("$key is too large")
        private fun JSONArray.mapObjects(transform: (JSONObject) -> OffCycle): List<OffCycle> = List(length()) { transform(getJSONObject(it)) }
        private fun JSONArray.mapStrings(): List<String> = List(length()) { getString(it).trim().takeIf(String::isNotBlank) ?: error("mini-program names must be non-blank") }
    }
}

data class M14SessionValidation(
    val collectionStartMatchesTraceCutoff: Boolean,
    val collectionWindow: CollectionWindowValidation?,
    val controlledRun: ControlledRunValidation?,
    val soak: SoakValidation?,
    val offCycleCount: Int,
    val validOffCycleCount: Int,
    val lifecycleViolationCodes: List<String>,
    val finalIoHealth: IoHealthValidation?,
    val coverage: CoverageValidation?,
    val stability: StabilityValidation?,
    val slowReview: SlowReviewValidation?,
) {
    val passed: Boolean
        get() = collectionStartMatchesTraceCutoff && collectionWindow?.passed == true && controlledRun?.passed == true && soak?.passed == true &&
            offCycleCount >= M14SessionSidecarValidator.REQUIRED_OFF_CYCLES && validOffCycleCount == offCycleCount && lifecycleViolationCodes.isEmpty() &&
            finalIoHealth?.passed == true && coverage?.passed == true && stability?.passed == true && slowReview?.passed == true

    fun toJson(): JSONObject = JSONObject()
        .put("collectionStartMatchesTraceCutoff", collectionStartMatchesTraceCutoff)
        .put("collectionWindow", collectionWindow?.toJson())
        .put("controlledRun", controlledRun?.toJson())
        .put("soak", soak?.toJson())
        .put("offCycleCount", offCycleCount)
        .put("validOffCycleCount", validOffCycleCount)
        .put("lifecycleViolationCodes", lifecycleViolationCodes)
        .put("finalIoHealth", finalIoHealth?.toJson())
        .put("coverage", coverage?.toJson())
        .put("stability", stability?.toJson())
        .put("slowReview", slowReview?.toJson())
        .put("passed", passed)
}

data class CollectionWindowValidation(val startEpochMs: Long, val endEpochMs: Long, val durationMs: Long) {
    val passed: Boolean get() = durationMs >= 0
    fun toJson(): JSONObject = JSONObject().put("startEpochMs", startEpochMs).put("endEpochMs", endEpochMs).put("durationMs", durationMs).put("passed", passed)
}

data class ControlledRunValidation(val durationMs: Long, val records: Int, val insideCollection: Boolean) {
    val passed: Boolean get() = durationMs >= M14SessionSidecarValidator.MIN_CONTROLLED_RUN_DURATION_MS && records >= M14SessionSidecarValidator.MINIMUM_RECORDS && insideCollection
    fun toJson(): JSONObject = JSONObject().put("durationMs", durationMs).put("records", records).put("insideCollection", insideCollection).put("passed", passed)
}

data class SoakValidation(val durationMs: Long, val continuousCollectionConfirmed: Boolean, val serviceBoundAtEnd: Boolean) {
    val passed: Boolean get() = durationMs >= M14SessionSidecarValidator.MIN_SOAK_DURATION_MS && continuousCollectionConfirmed && serviceBoundAtEnd
    fun toJson(): JSONObject = JSONObject().put("durationMs", durationMs).put("continuousCollectionConfirmed", continuousCollectionConfirmed).put("serviceBoundAtEnd", serviceBoundAtEnd).put("passed", passed)
}

data class IoHealthValidation(val pendingIoJobs: Int, val maxPendingIoJobs: Int, val recordsWritten: Long, val writeFailures: Long) {
    val passed: Boolean get() = pendingIoJobs == 0 && writeFailures == 0L
    fun toJson(): JSONObject = JSONObject().put("pendingIoJobs", pendingIoJobs).put("maxPendingIoJobs", maxPendingIoJobs).put("recordsWritten", recordsWritten).put("writeFailures", writeFailures).put("passed", passed)
}

data class CoverageValidation(val ordinaryAppCount: Int, val wechatMiniPrograms: Int, val alipayMiniPrograms: Int, val uniqueWechatNames: Boolean, val uniqueAlipayNames: Boolean) {
    val passed: Boolean get() = ordinaryAppCount >= 10 && wechatMiniPrograms >= 3 && alipayMiniPrograms >= 3 && uniqueWechatNames && uniqueAlipayNames
    fun toJson(): JSONObject = JSONObject().put("ordinaryAppCount", ordinaryAppCount).put("wechatMiniPrograms", wechatMiniPrograms).put("alipayMiniPrograms", alipayMiniPrograms).put("uniqueWechatNames", uniqueWechatNames).put("uniqueAlipayNames", uniqueAlipayNames).put("passed", passed)
}

data class StabilityValidation(val crashCount: Int, val anrCount: Int) {
    val passed: Boolean get() = crashCount == 0 && anrCount == 0
    fun toJson(): JSONObject = JSONObject().put("crashCount", crashCount).put("anrCount", anrCount).put("passed", passed)
}

data class SlowReviewValidation(val unexplainedSlowAccessibilityCount: Int, val repeatableMultiSecondBlockingCount: Int) {
    val passed: Boolean get() = unexplainedSlowAccessibilityCount == 0 && repeatableMultiSecondBlockingCount == 0
    fun toJson(): JSONObject = JSONObject().put("unexplainedSlowAccessibilityCount", unexplainedSlowAccessibilityCount).put("repeatableMultiSecondBlockingCount", repeatableMultiSecondBlockingCount).put("passed", passed)
}

internal object M14SessionSidecarValidator {
    fun validate(sidecar: M14SessionSidecar, trace: List<TraceObservation>, traceCutoffEpochMs: Long?): M14SessionValidation {
        val collectionEnd = sidecar.collectionEndEpochMs
        val collectionWindow = collectionEnd?.let { CollectionWindowValidation(sidecar.collectionStartEpochMs, it, it - sidecar.collectionStartEpochMs) }
        val controlledRun = sidecar.controlledRun?.let { run -> ControlledRunValidation(run.endEpochMs - run.startEpochMs, trace.count { it.epochMs in run.startEpochMs..run.endEpochMs }, collectionEnd != null && run.startEpochMs >= sidecar.collectionStartEpochMs && run.endEpochMs <= collectionEnd) }
        val soak = collectionWindow?.let { SoakValidation(it.durationMs, sidecar.continuousCollectionConfirmed == true, sidecar.serviceBoundAtEnd == true) }
        val codes = mutableListOf<String>()
        val modeOffAnchors = trace.filter { it.trigger == "CASE_END" && it.terminationReason == "MODE_OFF" }
            .groupingBy { it.epochMs }
            .eachCount()
        val usedModeOffAnchors = mutableSetOf<Long>()
        var validCycles = 0
        sidecar.offCycles.forEachIndexed { index, cycle ->
            val prefix = "OFF_CYCLE_${index + 1}"
            var valid = true
            if (collectionEnd == null || cycle.offAtEpochMs < sidecar.collectionStartEpochMs || cycle.shadowRestoredAtEpochMs > collectionEnd) { codes += "${prefix}_OUTSIDE_COLLECTION"; valid = false }
            val previous = sidecar.offCycles.getOrNull(index - 1)
            if (previous != null && cycle.offAtEpochMs <= previous.shadowRestoredAtEpochMs) { codes += "${prefix}_NOT_STRICTLY_AFTER_PREVIOUS"; valid = false }
            when (modeOffAnchors[cycle.offAtEpochMs] ?: 0) {
                0 -> { codes += "${prefix}_MISSING_MODE_OFF_ANCHOR"; valid = false }
                1 -> Unit
                else -> { codes += "${prefix}_AMBIGUOUS_MODE_OFF_ANCHOR"; valid = false }
            }
            if (!usedModeOffAnchors.add(cycle.offAtEpochMs)) { codes += "${prefix}_DUPLICATE_MODE_OFF_ANCHOR"; valid = false }
            if (cycle.shadowRestoredAtEpochMs - cycle.offAtEpochMs < MIN_OFF_DURATION_MS) { codes += "${prefix}_TOO_SHORT"; valid = false }
            if (trace.any { it.trigger == "SCAN" && it.epochMs in cycle.offAtEpochMs until cycle.shadowRestoredAtEpochMs }) { codes += "${prefix}_SCAN_WHILE_OFF"; valid = false }
            val nextOff = sidecar.offCycles.getOrNull(index + 1)?.offAtEpochMs ?: collectionEnd ?: Long.MAX_VALUE
            if (trace.none { it.trigger == "SCAN" && it.epochMs >= cycle.shadowRestoredAtEpochMs && it.epochMs < nextOff }) { codes += "${prefix}_NO_SCAN_AFTER_RESTORE"; valid = false }
            if (valid) validCycles++
        }
        if (sidecar.offCycles.size < REQUIRED_OFF_CYCLES) codes += "TOO_FEW_OFF_CYCLES"
        return M14SessionValidation(
            collectionStartMatchesTraceCutoff = traceCutoffEpochMs == null || sidecar.collectionStartEpochMs == traceCutoffEpochMs,
            collectionWindow = collectionWindow,
            controlledRun = controlledRun,
            soak = soak,
            offCycleCount = sidecar.offCycles.size,
            validOffCycleCount = validCycles,
            lifecycleViolationCodes = codes.sorted(),
            finalIoHealth = sidecar.finalIoHealth?.let { IoHealthValidation(it.pendingIoJobs, it.maxPendingIoJobs, it.recordsWritten, it.writeFailures) },
            coverage = sidecar.coverage?.let { CoverageValidation(it.ordinaryAppCount, it.wechatMiniProgramNames.size, it.alipayMiniProgramNames.size, it.wechatMiniProgramNames.distinct().size == it.wechatMiniProgramNames.size, it.alipayMiniProgramNames.distinct().size == it.alipayMiniProgramNames.size) },
            stability = sidecar.stability?.let { StabilityValidation(it.crashCount, it.anrCount) },
            slowReview = sidecar.slowReview?.let { SlowReviewValidation(it.unexplainedSlowAccessibilityCount, it.repeatableMultiSecondBlockingCount) },
        )
    }

    const val MINIMUM_RECORDS = 300
    const val MIN_CONTROLLED_RUN_DURATION_MS = 15L * 60L * 1_000L
    const val MIN_SOAK_DURATION_MS = 60L * 60L * 1_000L
    const val MIN_OFF_DURATION_MS = 30_000L
    const val REQUIRED_OFF_CYCLES = 5
}

internal data class TraceObservation(
    val epochMs: Long,
    val trigger: String,
    val caseId: String?,
    val terminationReason: String? = null,
)
