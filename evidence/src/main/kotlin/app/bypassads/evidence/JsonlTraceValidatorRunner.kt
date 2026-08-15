package app.bypassads.evidence

import app.bypassads.core.runtime.CaseTraceEntry
import app.bypassads.core.runtime.CaseTraceValidator
import app.bypassads.core.runtime.ShadowCaseEndReason
import java.nio.file.Files
import java.nio.file.Path
import org.json.JSONObject

data class JsonlTraceValidationReport(
    val inputLines: Int,
    val ignoredBeforeStart: Int,
    val malformedJsonLines: Int,
    val nonCaseRecords: Int,
    val caseRecords: Int,
    val unadaptableCaseRecords: Int,
    val openTailCaseCount: Int,
    val validatorViolations: List<String>,
    val evidenceSummary: JsonlEvidenceSummary,
    val sessionValidation: M14SessionValidation?,
) {
    val traceIntegrityPassed: Boolean
        get() = malformedJsonLines == 0 && unadaptableCaseRecords == 0 && validatorViolations.isEmpty()

    val scanMetricsComplete: Boolean
        get() = evidenceSummary.timings.values.all(MetricSummary::complete) &&
            evidenceSummary.captureBudget.complete &&
            evidenceSummary.workerBusyDrops.complete &&
            evidenceSummary.workerMaxQueueDepth.complete

    val releaseEvidenceReady: Boolean
        get() = traceIntegrityPassed &&
            scanMetricsComplete &&
            sessionValidation?.passed == true

    val passed: Boolean
        get() = traceIntegrityPassed && (sessionValidation?.passed ?: true)

    fun toJson(): String = JSONObject()
        .put("inputLines", inputLines)
        .put("ignoredBeforeStart", ignoredBeforeStart)
        .put("malformedJsonLines", malformedJsonLines)
        .put("nonCaseRecords", nonCaseRecords)
        .put("caseRecords", caseRecords)
        .put("unadaptableCaseRecords", unadaptableCaseRecords)
        .put("openTailCaseCount", openTailCaseCount)
        .put("validatorViolationCount", validatorViolations.size)
        .put("evidenceSummary", evidenceSummary.toJson())
        .put("sessionValidation", sessionValidation?.toJson())
        .put("traceIntegrityPassed", traceIntegrityPassed)
        .put("scanMetricsComplete", scanMetricsComplete)
        .put("releaseEvidenceReady", releaseEvidenceReady)
        .put("passed", passed)
        .toString()
}

object JsonlTraceValidatorRunner {
    fun validate(lines: Sequence<String>, fromEpochMs: Long? = null, sidecar: M14SessionSidecar? = null): JsonlTraceValidationReport {
        var inputLines = 0
        var ignoredBeforeStart = 0
        var malformedJsonLines = 0
        var nonCaseRecords = 0
        var caseRecords = 0
        var unadaptableCaseRecords = 0
        var lastPostCutoffRecordWasCaseEntry = false
        val entries = mutableListOf<CaseTraceEntry>()
        val evidenceSummary = JsonlEvidenceSummaryCollector()
        val traceObservations = mutableListOf<TraceObservation>()

        lines.forEach { line ->
            inputLines++
            val json = runCatching { JSONObject(line) }.getOrElse {
                malformedJsonLines++
                return@forEach
            }
            val epochMs = runCatching { json.getLong("time") }.getOrElse {
                malformedJsonLines++
                return@forEach
            }
            if (fromEpochMs != null && epochMs < fromEpochMs) {
                ignoredBeforeStart++
                return@forEach
            }
            lastPostCutoffRecordWasCaseEntry = false
            evidenceSummary.recordSeen(epochMs)
            val trigger = runCatching { json.traceTrigger() }.getOrElse {
                unadaptableCaseRecords++
                return@forEach
            }
            val caseId = json.nullableString("caseId")
            evidenceSummary.recordKnownTrigger(json, trigger.name, caseId)
            traceObservations += TraceObservation(epochMs, trigger.name, caseId, json.nullableString("terminationReason"))
            if (caseId == null) {
                if (trigger == TraceTrigger.SERVICE_CONNECTED) nonCaseRecords++ else unadaptableCaseRecords++
                return@forEach
            }
            caseRecords++
            val entry = runCatching { json.toTraceEntry(trigger, caseId) }.getOrElse {
                unadaptableCaseRecords++
                return@forEach
            }
            entries += entry
            lastPostCutoffRecordWasCaseEntry = true
        }

        // A local copy can end while the final one-second burst is still open.
        val openTailCaseId = entries.lastOrNull()
            ?.takeIf { lastPostCutoffRecordWasCaseEntry && !it.isScan && !it.isEnd }
            ?.caseId
            ?.takeIf { candidateId -> entries.count { it.caseId == candidateId } == 1 }
        val entriesForValidation = openTailCaseId?.let { candidateId -> entries.filterNot { it.caseId == candidateId } } ?: entries

        return JsonlTraceValidationReport(
            inputLines = inputLines,
            ignoredBeforeStart = ignoredBeforeStart,
            malformedJsonLines = malformedJsonLines,
            nonCaseRecords = nonCaseRecords,
            caseRecords = caseRecords,
            unadaptableCaseRecords = unadaptableCaseRecords,
            openTailCaseCount = if (openTailCaseId == null) 0 else 1,
            validatorViolations = CaseTraceValidator.violations(entriesForValidation),
            evidenceSummary = evidenceSummary.summary(),
            sessionValidation = sidecar?.let { M14SessionSidecarValidator.validate(it, traceObservations, fromEpochMs) },
        )
    }

    private fun JSONObject.traceTrigger(): TraceTrigger = TraceTrigger.valueOf(getString("trigger"))

    private fun JSONObject.toTraceEntry(trigger: TraceTrigger, caseId: String): CaseTraceEntry {
        return CaseTraceEntry(
            caseId = caseId,
            sessionId = getLong("session"),
            scanIndex = getInt("scan"),
            isScan = trigger == TraceTrigger.SCAN,
            isEnd = trigger == TraceTrigger.CASE_END,
            scheduled = nullableInt("scheduledFrames"),
            executed = nullableInt("executedFrames"),
            cancelled = nullableInt("cancelledFrames"),
            dropped = nullableInt("droppedFrames"),
            terminationReason = nullableString("terminationReason")?.let { ShadowCaseEndReason.valueOf(it) },
        )
    }

    private fun JSONObject.nullableInt(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
    private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotBlank)

    private enum class TraceTrigger {
        SERVICE_CONNECTED,
        PACKAGE_CHANGED,
        WINDOW_CHANGED,
        WINDOW_STATE_CHANGED,
        WINDOWS_CHANGED,
        CONTENT_CHANGED,
        SCAN,
        /** M2.2 experimental actuation attempt: ordinary mid-case record (not a scan, not an end). */
        ACTION_ATTEMPT,
        CASE_END,
    }

}

fun main(args: Array<String>) {
    val options = parseOptions(args)
    val input = options["--input"]?.let(Path::of) ?: error("Usage: --input <jsonl-path> [--from-epoch-ms <epoch-ms>] [--session-sidecar <json-path>]")
    val fromEpochMs = options["--from-epoch-ms"]?.toLongOrNull()
        ?: options["--from-epoch-ms"]?.let { error("--from-epoch-ms must be an integer") }
    val sidecar = options["--session-sidecar"]?.let { path ->
        Files.newBufferedReader(Path.of(path)).use { reader -> M14SessionSidecar.parse(JSONObject(reader.readText())) }
    }
    val report = Files.newBufferedReader(input).use { reader ->
        JsonlTraceValidatorRunner.validate(reader.lineSequence(), fromEpochMs, sidecar)
    }
    println(report.toJson())
    check(report.passed) { "JSONL trace validation failed" }
}

internal fun parseOptions(args: Array<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val key = args[index]
        require(key == "--input" || key == "--from-epoch-ms" || key == "--session-sidecar") { "Unknown option: $key" }
        val value = args.getOrNull(index + 1) ?: error("Missing value for $key")
        require(!options.containsKey(key)) { "Duplicate option: $key" }
        options[key] = value
        index += 2
    }
    return options
}
