package app.bypassads.diagnostics

import android.content.Context
import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.IntRect
import app.bypassads.core.model.WeightedReason
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

class BlackBoxStore(context: Context) {
    private val dir = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    fun append(record: BlackBoxRecord) = synchronized(FILE_LOCK) {
        val day = dayFor(record.epochMs)
        val bytes = (record.toJsonLine() + "\n").toByteArray(Charsets.UTF_8)
        FileOutputStream(File(dir, "$day.jsonl"), true).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        enforceRetention()
    }

    fun recentRecords(limit: Int = DEFAULT_RECENT_LIMIT): List<DiagnosticRecord> = recordFiles()
        .sortedByDescending { it.name }
        .asSequence()
        .flatMap { file -> file.readLines(Charsets.UTF_8).asReversed().asSequence() }
        .mapNotNull(::parseRecordOrNull)
        .take(limit)
        .toList()

    fun todayStats(): BlackBoxStats {
        val todayRecords = File(dir, "${LocalDate.now()}.jsonl")
            .takeIf(File::exists)
            ?.readLines(Charsets.UTF_8)
            ?.mapNotNull(::parseRecordOrNull)
            .orEmpty()
            .filter { it.trigger == BlackBoxTrigger.SCAN }
        return BlackBoxStats(
            recordCount = todayRecords.size,
            wouldClickCount = todayRecords.count { it.decision == "WOULD_CLICK" },
            rejectedCount = todayRecords.count { it.decision == "TOO_RISKY" },
            latestDecision = todayRecords.maxByOrNull { it.epochMs },
        )
    }

    fun clear() = synchronized(FILE_LOCK) {
        recordFiles().forEach(File::delete)
    }

    private fun recordFiles(): List<File> = dir.listFiles()
        .orEmpty()
        .filter { it.extension == "jsonl" }

    private fun enforceRetention() {
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS)
        recordFiles().forEach { file ->
            val date = runCatching { LocalDate.parse(file.nameWithoutExtension) }.getOrNull()
            if (date != null && date.isBefore(cutoff)) file.delete()
        }
        val remaining = recordFiles().sortedBy { it.name }
        var bytes = remaining.sumOf(File::length)
        for (file in remaining) {
            if (bytes <= MAX_BYTES) break
            bytes -= file.length()
            file.delete()
        }
    }

    private fun dayFor(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()

    private fun BlackBoxRecord.toJsonLine(): String = JSONObject().apply {
        put("time", epochMs)
        put("session", sessionId)
        put("scan", scanIndex)
        put("package", packageName ?: JSONObject.NULL)
        put("trigger", trigger.name)
        put("sourceTrigger", sourceTrigger?.name ?: JSONObject.NULL)
        put("windows", windowCount)
        put("nodes", nodeCount)
        put("candidates", candidateFeatures.size)
        put("decision", decision?.type?.name ?: JSONObject.NULL)
        put("rejectionReason", decision?.rejectionReason ?: JSONObject.NULL)
        put("score", decision?.candidate?.score ?: JSONObject.NULL)
        put("evidence", decision?.candidate?.evidence.toReasonsJson())
        put("risks", decision?.candidate?.risks.toReasonsJson())
        put("features", JSONArray().apply {
            candidateFeatures.take(MAX_FEATURES_PER_SCAN).forEach { put(it.toJson()) }
        })
        put("latencyMs", latencyMs ?: JSONObject.NULL)
    }.toString()

    private fun CandidateFeatures.toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("resourceId", resourceId ?: JSONObject.NULL)
        put("bounds", bounds.toJson())
        put("clickable", clickable)
        put("ancestor", nearestClickableAncestorBounds?.toJson() ?: JSONObject.NULL)
        put("screenW", screenWidth)
        put("screenH", screenHeight)
        put("hits", stabilityHits)
        put("countdown", countdownValue ?: JSONObject.NULL)
        put("countdownStep", countdownStepObserved)
        put("positionDrift", positionDriftDetected)
        put("ctaSibling", ctaSiblingDetected)
    }

    private fun IntRect.toJson(): JSONArray = JSONArray().apply {
        put(left)
        put(top)
        put(right)
        put(bottom)
    }

    private fun List<WeightedReason>?.toReasonsJson(): JSONArray = JSONArray().apply {
        this@toReasonsJson.orEmpty().forEach { reason ->
            put(JSONObject().put("code", reason.code).put("points", reason.points))
        }
    }

    private fun parseRecordOrNull(line: String): DiagnosticRecord? = runCatching {
        val json = JSONObject(line)
        DiagnosticRecord(
            epochMs = json.getLong("time"),
            sessionId = json.optLong("session"),
            scanIndex = json.optInt("scan"),
            packageName = json.nullableString("package"),
            trigger = json.enumOrDefault("trigger", BlackBoxTrigger.SCAN),
            sourceTrigger = json.nullableEnum("sourceTrigger"),
            windowCount = json.optInt("windows"),
            nodeCount = json.optInt("nodes"),
            candidateCount = json.optInt("candidates"),
            decision = json.nullableString("decision"),
            rejectionReason = json.nullableString("rejectionReason"),
            score = json.nullableInt("score"),
            evidence = json.optJSONArray("evidence").toReasons(),
            risks = json.optJSONArray("risks").toReasons(),
            candidates = json.optJSONArray("features").toCandidates(),
            latencyMs = json.nullableLong("latencyMs"),
        )
    }.getOrNull()

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun JSONObject.nullableInt(key: String): Int? =
        if (isNull(key) || !has(key)) null else optInt(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun JSONObject.enumOrDefault(key: String, default: BlackBoxTrigger): BlackBoxTrigger =
        runCatching { BlackBoxTrigger.valueOf(getString(key)) }.getOrDefault(default)

    private fun JSONObject.nullableEnum(key: String): BlackBoxTrigger? =
        nullableString(key)?.let { runCatching { BlackBoxTrigger.valueOf(it) }.getOrNull() }

    private fun JSONArray?.toReasons(): List<DiagnosticReason> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                add(DiagnosticReason(json.optString("code"), json.optInt("points")))
            }
        }
    }

    private fun JSONArray?.toCandidates(): List<DiagnosticCandidate> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val bounds = json.optJSONArray("bounds")
                add(
                    DiagnosticCandidate(
                        label = json.optString("label", "matched"),
                        resourceId = json.nullableString("resourceId"),
                        bounds = List(bounds?.length() ?: 0) { bounds?.optInt(it) ?: 0 },
                        clickable = json.optBoolean("clickable"),
                        stabilityHits = json.optInt("hits", 1),
                        countdownValue = json.nullableInt("countdown"),
                        countdownStepObserved = json.optBoolean("countdownStep"),
                        positionDriftDetected = json.optBoolean("positionDrift"),
                        ctaSiblingDetected = json.optBoolean("ctaSibling"),
                    ),
                )
            }
        }
    }

    private companion object {
        val FILE_LOCK = Any()
        const val DIRECTORY_NAME = "bypass_ads_blackbox"
        const val RETENTION_DAYS = 7L
        const val MAX_BYTES = 20L * 1024L * 1024L
        const val MAX_FEATURES_PER_SCAN = 8
        const val DEFAULT_RECENT_LIMIT = 50
    }
}
