package app.bypassads.diagnostics

import android.content.Context
import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.IntRect
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BlackBoxStore(context: Context) {
    private val dir = File(context.filesDir, "blackbox").apply { mkdirs() }

    @Synchronized
    fun append(record: BlackBoxRecord) {
        val day = Instant.ofEpochMilli(record.epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        File(dir, "$day.jsonl").appendText(record.toJsonLine() + "\n", Charsets.UTF_8)
        enforceRetention()
    }

    fun recentLines(limit: Int = 12): List<String> = dir.listFiles()
        .orEmpty().filter { it.extension == "jsonl" }.sortedByDescending { it.name }
        .flatMap { it.readLines(Charsets.UTF_8).asReversed() }.take(limit)

    private fun enforceRetention() {
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS)
        dir.listFiles().orEmpty().forEach { file ->
            val date = runCatching { LocalDate.parse(file.nameWithoutExtension) }.getOrNull()
            if (date != null && date.isBefore(cutoff)) file.delete()
        }
        val remaining = dir.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedBy { it.name }
        var bytes = remaining.sumOf { it.length() }
        for (file in remaining) {
            if (bytes <= MAX_BYTES) break
            bytes -= file.length(); file.delete()
        }
    }

    private fun BlackBoxRecord.toJsonLine(): String {
        val candidate = decision.candidate
        val evidence = candidate?.evidence?.reasonsJson() ?: "[]"
        val risks = candidate?.risks?.reasonsJson() ?: "[]"
        val features = candidateFeatures.take(MAX_FEATURES_PER_SCAN).joinToString(prefix = "[", postfix = "]") { it.toJson() }
        return buildString {
            append('{')
            append("\"time\":").append(epochMs).append(',')
            append("\"session\":").append(sessionId).append(',')
            append("\"scan\":").append(scanIndex).append(',')
            append("\"package\":").append(packageName.json()).append(',')
            append("\"windows\":").append(windowCount).append(',')
            append("\"nodes\":").append(nodeCount).append(',')
            append("\"candidates\":").append(candidateFeatures.size).append(',')
            append("\"decision\":\"").append(decision.type.name).append("\",")
            append("\"score\":").append(candidate?.score ?: "null").append(',')
            append("\"evidence\":").append(evidence).append(',')
            append("\"risks\":").append(risks).append(',')
            append("\"features\":").append(features)
            append('}')
        }
    }

    private fun CandidateFeatures.toJson(): String = buildString {
        append('{')
        append("\"label\":").append(label.json()).append(',')
        append("\"resourceId\":").append(resourceId.json()).append(',')
        append("\"bounds\":").append(bounds.json()).append(',')
        append("\"clickable\":").append(clickable).append(',')
        append("\"ancestor\":").append(nearestClickableAncestorBounds?.json() ?: "null").append(',')
        append("\"screenW\":").append(screenWidth).append(',')
        append("\"screenH\":").append(screenHeight).append(',')
        append("\"hits\":").append(stabilityHits).append(',')
        append("\"countdown\":").append(countdownValue ?: "null").append(',')
        append("\"countdownStep\":").append(countdownStepObserved).append(',')
        append("\"ctaSibling\":").append(ctaSiblingDetected)
        append('}')
    }

    private fun IntRect.json(): String = "[$left,$top,$right,$bottom]"
    private fun List<app.bypassads.core.model.WeightedReason>.reasonsJson(): String = joinToString(prefix = "[", postfix = "]") {
        "{\"code\":\"${escape(it.code)}\",\"points\":${it.points}}"
    }
    private fun String?.json(): String = if (this == null) "null" else "\"${escape(this)}\""
    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val RETENTION_DAYS = 7L
        const val MAX_BYTES = 20L * 1024L * 1024L
        const val MAX_FEATURES_PER_SCAN = 8
    }
}
