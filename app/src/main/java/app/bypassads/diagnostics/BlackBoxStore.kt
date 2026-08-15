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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** All file work is serialized away from accessibility and Compose main paths. */
class BlackBoxStore(context: Context) {
    private val repository = BlackBoxRuntime.repository(context.applicationContext)

    fun append(record: BlackBoxRecord) = repository.append(record)
    fun recentRecords(limit: Int = DEFAULT_RECENT_LIMIT, callback: (List<DiagnosticRecord>) -> Unit) = repository.recentRecords(limit, callback)
    fun todayStats(callback: (BlackBoxStats) -> Unit) = repository.todayStats(callback)
    fun clear(callback: (() -> Unit)? = null) = repository.clear(callback)
    fun health(callback: (BlackBoxIoHealth) -> Unit) = repository.health(callback)

    /** Repository lifetime is the application process, not an Activity or Service client. */
    fun close() = Unit

    private companion object { const val DEFAULT_RECENT_LIMIT = 50 }
}

data class BlackBoxIoHealth(
    val pendingIoJobs: Int,
    val maxPendingIoJobs: Int,
    val recordsWritten: Long,
    val writeFailures: Long,
)

internal object BlackBoxRuntime {
    @Volatile private var shared: BlackBoxRepository? = null

    fun repository(context: Context): BlackBoxRepository = shared ?: synchronized(this) {
        shared ?: BlackBoxRepository(File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }).also { shared = it }
    }

    private const val DIRECTORY_NAME = "bypass_ads_blackbox"
}

class BlackBoxRepository internal constructor(private val dir: File) {
    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bypass-ads-blackbox").apply { isDaemon = true }
    }
    private val pendingJobs = AtomicInteger()
    private val maxPendingJobs = AtomicInteger()
    private var recordsWritten = 0L
    private var writeFailures = 0L
    private var appendCountSinceRetention = 0
    private var lastRetentionDay: LocalDate? = null
    private var trackedBytes = dir.listFiles().orEmpty().filter { it.extension == "jsonl" }.sumOf(File::length)

    fun append(record: BlackBoxRecord) {
        enqueue {
            val day = dayFor(record.epochMs)
            val bytes = (BlackBoxJsonCodec.encode(record) + "\n").toByteArray(Charsets.UTF_8)
            runCatching {
                FileOutputStream(File(dir, "$day.jsonl"), true).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
                recordsWritten++
                trackedBytes += bytes.size
                appendCountSinceRetention++
                val dayFile = File(dir, "$day.jsonl")
                if (lastRetentionDay != day || appendCountSinceRetention >= RETENTION_INTERVAL || trackedBytes > MAX_BYTES) {
                    enforceRetention()
                    appendCountSinceRetention = 0
                    lastRetentionDay = day
                }
            }.onFailure { writeFailures++ }
        }
    }

    fun recentRecords(limit: Int = DEFAULT_RECENT_LIMIT, callback: (List<DiagnosticRecord>) -> Unit) {
        enqueue { callback(readRecentRecords(limit)) }
    }

    fun todayStats(callback: (BlackBoxStats) -> Unit) {
        enqueue { callback(readTodayStats()) }
    }

    fun clear(callback: (() -> Unit)? = null) {
        enqueue {
            recordFiles().forEach(File::delete)
            trackedBytes = 0L
            callback?.invoke()
        }
    }

    fun health(callback: (BlackBoxIoHealth) -> Unit) {
        enqueue { callback(BlackBoxIoHealth((pendingJobs.get() - 1).coerceAtLeast(0), maxPendingJobs.get(), recordsWritten, writeFailures)) }
    }

    private fun enqueue(work: () -> Unit) {
        val pending = pendingJobs.incrementAndGet()
        maxPendingJobs.accumulateAndGet(pending, ::maxOf)
        io.execute {
            try { work() } finally { pendingJobs.decrementAndGet() }
        }
    }

    private fun readRecentRecords(limit: Int): List<DiagnosticRecord> = recordFiles()
        .sortedByDescending { it.name }
        .asSequence()
        .flatMap { file -> file.readLines(Charsets.UTF_8).asReversed().asSequence() }
        .mapNotNull(BlackBoxJsonCodec::decodeOrNull)
        .take(limit)
        .toList()

    private fun readTodayStats(): BlackBoxStats {
        val todayRecords = File(dir, "${LocalDate.now()}.jsonl")
            .takeIf(File::exists)
            ?.readLines(Charsets.UTF_8)
            ?.mapNotNull(BlackBoxJsonCodec::decodeOrNull)
            .orEmpty()
            .filter { it.trigger == BlackBoxTrigger.SCAN }
        return BlackBoxStats(
            recordCount = todayRecords.size,
            wouldClickCount = todayRecords.count { it.decision == "WOULD_CLICK" },
            rejectedCount = todayRecords.count { it.decision == "TOO_RISKY" },
            latestDecision = todayRecords.maxByOrNull { it.epochMs },
        )
    }

    private fun recordFiles(): List<File> = dir.listFiles().orEmpty().filter { it.extension == "jsonl" }

    private fun enforceRetention() {
        val cutoff = LocalDate.now().minusDays(RETENTION_DAYS)
        recordFiles().forEach { file ->
            val date = runCatching { LocalDate.parse(file.nameWithoutExtension) }.getOrNull()
            val length = file.length()
            if (date != null && date.isBefore(cutoff) && file.delete()) trackedBytes -= length
        }
        var bytes = recordFiles().sumOf(File::length)
        recordFiles().sortedBy { it.name }.forEach { file ->
            if (bytes > MAX_BYTES) {
                val length = file.length()
                bytes -= length
                if (file.delete()) trackedBytes -= length
            }
        }
    }

    private fun dayFor(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

    private companion object {
        const val DIRECTORY_NAME = "bypass_ads_blackbox"
        const val RETENTION_DAYS = 7L
        const val MAX_BYTES = 20L * 1024L * 1024L
        const val DEFAULT_RECENT_LIMIT = 50
        const val RETENTION_INTERVAL = 64
    }
}

object BlackBoxJsonCodec {
    fun encode(record: BlackBoxRecord): String = JSONObject().apply {
        put("time", record.epochMs); put("session", record.sessionId); put("scan", record.scanIndex)
        put("caseId", record.caseId ?: JSONObject.NULL)
        put("package", record.packageName ?: JSONObject.NULL); put("trigger", record.trigger.name)
        put("sourceTrigger", record.sourceTrigger?.name ?: JSONObject.NULL); put("windows", record.windowCount); put("nodes", record.nodeCount)
        put("candidates", record.candidateFeatures.size); put("decision", record.decision?.type?.name ?: JSONObject.NULL)
        put("rejectionReason", record.decision?.rejectionReason ?: JSONObject.NULL); put("score", record.decision?.candidate?.score ?: JSONObject.NULL)
        put("evidence", record.decision?.candidate?.evidence.toReasonsJson()); put("risks", record.decision?.candidate?.risks.toReasonsJson())
        put("features", JSONArray().apply { record.candidateFeatures.take(MAX_FEATURES_PER_SCAN).forEach { put(it.toJson()) } })
        put("latencyMs", record.latencyMs ?: JSONObject.NULL); put("scanWorkMs", record.scanWorkMs ?: JSONObject.NULL)
    }.toString()

    fun decodeOrNull(line: String): DiagnosticRecord? = runCatching {
        val json = JSONObject(line)
        DiagnosticRecord(
            epochMs = json.getLong("time"), sessionId = json.optLong("session"), caseId = json.nullableString("caseId"), scanIndex = json.optInt("scan"),
            packageName = json.nullableString("package"), trigger = json.enumOrDefault("trigger", BlackBoxTrigger.SCAN),
            sourceTrigger = json.nullableEnum("sourceTrigger"), windowCount = json.optInt("windows"), nodeCount = json.optInt("nodes"),
            candidateCount = json.optInt("candidates"), decision = json.nullableString("decision"), rejectionReason = json.nullableString("rejectionReason"),
            score = json.nullableInt("score"), evidence = json.optJSONArray("evidence").toReasons(), risks = json.optJSONArray("risks").toReasons(),
            candidates = json.optJSONArray("features").toCandidates(), latencyMs = json.nullableLong("latencyMs"), scanWorkMs = json.nullableLong("scanWorkMs"),
        )
    }.getOrNull()

    private fun CandidateFeatures.toJson(): JSONObject = JSONObject().apply {
        put("label", label); put("resourceId", resourceId ?: JSONObject.NULL); put("bounds", bounds.toJson()); put("clickable", clickable)
        put("ancestor", nearestClickableAncestorBounds?.toJson() ?: JSONObject.NULL); put("screenW", screenWidth); put("screenH", screenHeight)
        put("hits", stabilityHits); put("countdown", countdownValue ?: JSONObject.NULL); put("countdownStep", countdownStepObserved)
        put("positionDrift", positionDriftDetected); put("ctaSibling", ctaSiblingDetected); put("identityAmbiguous", identityAmbiguous)
    }

    private fun IntRect.toJson(): JSONArray = JSONArray().apply { put(left); put(top); put(right); put(bottom) }
    private fun List<WeightedReason>?.toReasonsJson(): JSONArray = JSONArray().apply { this@toReasonsJson.orEmpty().forEach { put(JSONObject().put("code", it.code).put("points", it.points)) } }
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
    private fun JSONObject.nullableLong(key: String): Long? = if (isNull(key) || !has(key)) null else optLong(key)
    private fun JSONObject.enumOrDefault(key: String, default: BlackBoxTrigger): BlackBoxTrigger = runCatching { BlackBoxTrigger.valueOf(getString(key)) }.getOrDefault(default)
    private fun JSONObject.nullableEnum(key: String): BlackBoxTrigger? = nullableString(key)?.let { runCatching { BlackBoxTrigger.valueOf(it) }.getOrNull() }
    private fun JSONArray?.toReasons(): List<DiagnosticReason> = buildList { for (i in 0 until (this@toReasons?.length() ?: 0)) { val item = this@toReasons?.optJSONObject(i) ?: continue; add(DiagnosticReason(item.optString("code"), item.optInt("points"))) } }
    private fun JSONArray?.toCandidates(): List<DiagnosticCandidate> = buildList {
        for (i in 0 until (this@toCandidates?.length() ?: 0)) {
            val item = this@toCandidates?.optJSONObject(i) ?: continue; val bounds = item.optJSONArray("bounds")
            add(DiagnosticCandidate(item.optString("label", "matched"), item.nullableString("resourceId"), List(bounds?.length() ?: 0) { bounds?.optInt(it) ?: 0 }, item.optBoolean("clickable"), item.optInt("hits", 1), item.nullableInt("countdown"), item.optBoolean("countdownStep"), item.optBoolean("positionDrift"), item.optBoolean("ctaSibling"), item.optBoolean("identityAmbiguous")))
        }
    }

    private const val MAX_FEATURES_PER_SCAN = 8
}
