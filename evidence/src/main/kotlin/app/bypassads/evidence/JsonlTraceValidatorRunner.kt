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
    val validatorViolations: List<String>,
) {
    val passed: Boolean
        get() = malformedJsonLines == 0 && unadaptableCaseRecords == 0 && validatorViolations.isEmpty()

    fun toJson(): String = JSONObject()
        .put("inputLines", inputLines)
        .put("ignoredBeforeStart", ignoredBeforeStart)
        .put("malformedJsonLines", malformedJsonLines)
        .put("nonCaseRecords", nonCaseRecords)
        .put("caseRecords", caseRecords)
        .put("unadaptableCaseRecords", unadaptableCaseRecords)
        .put("validatorViolationCount", validatorViolations.size)
        .put("passed", passed)
        .toString()
}

object JsonlTraceValidatorRunner {
    fun validate(lines: Sequence<String>, fromEpochMs: Long? = null): JsonlTraceValidationReport {
        var inputLines = 0
        var ignoredBeforeStart = 0
        var malformedJsonLines = 0
        var nonCaseRecords = 0
        var caseRecords = 0
        var unadaptableCaseRecords = 0
        val entries = mutableListOf<CaseTraceEntry>()

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
            if (json.isNull("caseId") || !json.has("caseId")) {
                nonCaseRecords++
                return@forEach
            }
            caseRecords++
            val entry = runCatching { json.toTraceEntry() }.getOrElse {
                unadaptableCaseRecords++
                return@forEach
            }
            entries += entry
        }

        return JsonlTraceValidationReport(
            inputLines = inputLines,
            ignoredBeforeStart = ignoredBeforeStart,
            malformedJsonLines = malformedJsonLines,
            nonCaseRecords = nonCaseRecords,
            caseRecords = caseRecords,
            unadaptableCaseRecords = unadaptableCaseRecords,
            validatorViolations = CaseTraceValidator.violations(entries),
        )
    }

    private fun JSONObject.toTraceEntry(): CaseTraceEntry {
        val trigger = runCatching { TraceTrigger.valueOf(getString("trigger")) }.getOrElse {
            throw IllegalArgumentException("Unknown trigger")
        }
        return CaseTraceEntry(
            caseId = getString("caseId"),
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
    private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    private enum class TraceTrigger {
        SERVICE_CONNECTED,
        PACKAGE_CHANGED,
        WINDOW_CHANGED,
        WINDOW_STATE_CHANGED,
        WINDOWS_CHANGED,
        CONTENT_CHANGED,
        SCAN,
        CASE_END,
    }
}

fun main(args: Array<String>) {
    val options = parseOptions(args)
    val input = options["--input"]?.let(Path::of) ?: error("Usage: --input <jsonl-path> [--from-epoch-ms <epoch-ms>]")
    val fromEpochMs = options["--from-epoch-ms"]?.toLongOrNull()
        ?: options["--from-epoch-ms"]?.let { error("--from-epoch-ms must be an integer") }
    val report = Files.newBufferedReader(input).use { reader ->
        JsonlTraceValidatorRunner.validate(reader.lineSequence(), fromEpochMs)
    }
    println(report.toJson())
    check(report.passed) { "JSONL trace validation failed" }
}

internal fun parseOptions(args: Array<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val key = args[index]
        require(key == "--input" || key == "--from-epoch-ms") { "Unknown option: $key" }
        val value = args.getOrNull(index + 1) ?: error("Missing value for $key")
        require(!options.containsKey(key)) { "Duplicate option: $key" }
        options[key] = value
        index += 2
    }
    return options
}
