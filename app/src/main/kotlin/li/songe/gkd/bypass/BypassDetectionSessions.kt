package li.songe.gkd.bypass

import android.graphics.Rect
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import li.songe.gkd.appScope
import li.songe.gkd.data.BypassDetectionSession
import li.songe.gkd.db.DbSet
import li.songe.gkd.util.json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A deliberately small, non-content snapshot of a skip/close control. */
@Serializable
data class BypassCandidateSnapshot(
    val text: String? = null,
    val description: String? = null,
    val viewId: String? = null,
    val className: String,
    val bounds: String,
    val clickable: Boolean,
    val parentClickable: Boolean,
    val packageName: String,
    val activityName: String?,
)

/**
 * Turns matcher evidence into one durable result per app/activity window. Raw
 * matcher diagnostics remain in [BypassDiagnostics]; they never create a user
 * record on their own.
 */
object BypassDetectionSessions {
    private const val SESSION_WINDOW_MS = 8_000L
    private const val SESSION_HARD_TIMEOUT_MS = 15_000L
    private const val FINALIZE_DELAY_MS = 1_500L
    private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_TIMELINE_ITEMS = 24
    private const val MAX_RULES = 8
    private const val MAX_ACTIONS = 8
    private const val MAX_SNAPSHOTS = 8

    private data class ActiveSession(
        val record: BypassDetectionSession,
        val lastTouched: Long,
    )

    private val lock = Any()
    private val persistMutex = Mutex()
    private val scheduledFinalizers = ConcurrentHashMap<String, Job>()
    private var active: ActiveSession? = null

    fun targetFound(
        packageName: String,
        activityName: String?,
        ruleLabel: String,
        target: AccessibilityNodeInfo,
    ) {
        val snapshot = BypassCandidateSanitizer.snapshot(target, packageName, activityName)
        update(packageName, activityName, "TARGET_FOUND", create = true) { record ->
            record.copy(
                candidateSeen = true,
                matchedRules = append(record.matchedRules, ruleLabel, MAX_RULES),
                candidateSnapshots = snapshot?.let {
                    encodeSnapshots(decodeSnapshots(record.candidateSnapshots) + it)
                } ?: record.candidateSnapshots,
            )
        }
    }

    fun waitingForDelay(packageName: String, activityName: String?) {
        val session = update(packageName, activityName, "WAITING_FOR_DELAY", create = false)
        session?.let { scheduleFinalization(it.sessionId, FailureReason.ACTION_TOO_EARLY, SESSION_WINDOW_MS) }
    }

    fun targetNotClickable(packageName: String, activityName: String?) {
        val session = update(packageName, activityName, "TARGET_FOUND_NOT_CLICKABLE", create = false)
        session?.let { scheduleFinalization(it.sessionId, FailureReason.TARGET_FOUND_NOT_CLICKABLE) }
    }

    /** A generic candidate was blocked by the strategy gate (debug-only). */
    fun candidateRejected(packageName: String, activityName: String?, candidateType: String, reason: String) {
        update(packageName, activityName, "CLOSE_CANDIDATE_REJECTED:$candidateType:$reason", create = false)
    }

    /** Record the active strategy mode for the current window. */
    fun strategyApplied(packageName: String, activityName: String?, mode: BypassAdStrategyMode) {
        update(packageName, activityName, "STRATEGY:${mode.name}", create = false)
    }

    /** Record a bounded exit retry (second/third attempt). */
    fun exitRetry(packageName: String, activityName: String?, attempt: Int) {
        update(packageName, activityName, "EXIT_RETRY:$attempt", create = false)
    }

    fun actionSucceeded(packageName: String, activityName: String?, action: String) {
        val session = update(packageName, activityName, "ACTION_SUCCEEDED:$action", create = false) { record ->
            record.copy(
                actions = append(record.actions, action, MAX_ACTIONS),
                success = true,
                endTime = System.currentTimeMillis(),
                // A successful accessibility action resolves any provisional
                // failure, including a prior not-clickable observation.
                finalFailureReason = null,
            )
        }
        session?.let { scheduledFinalizers.remove(it.sessionId)?.cancel() }
    }

    fun actionFailed(packageName: String, activityName: String?, action: String) {
        val session = update(packageName, activityName, "ACTION_FAILED:$action", create = false) { record ->
            record.copy(actions = append(record.actions, action, MAX_ACTIONS))
        }
        session?.let { scheduleFinalization(it.sessionId, FailureReason.ACTION_FAILED) }
    }

    private fun update(
        packageName: String,
        activityName: String?,
        event: String,
        create: Boolean,
        transform: (BypassDetectionSession) -> BypassDetectionSession = { it },
    ): BypassDetectionSession? = synchronized(lock) {
        val now = System.currentTimeMillis()
        val current = active
        val matching = current?.takeIf {
            it.record.packageName == packageName &&
                it.record.activityName == activityName &&
                now - it.lastTouched <= SESSION_WINDOW_MS &&
                // Hard ceiling: a window that outlives the opening period is
                // closed instead of being kept alive forever (task 104).
                now - it.record.startTime <= SESSION_HARD_TIMEOUT_MS
        }
        if (matching == null && current != null) {
            // A context change ends a partial session without presenting it as a
            // failure. Evidence alone is not enough to accuse a missed ad.
            persist(current.record.copy(endTime = now))
            active = null
        }
        val base = matching ?: if (create) {
            ActiveSession(
                BypassDetectionSession(
                    sessionId = UUID.randomUUID().toString(),
                    packageName = packageName,
                    activityName = activityName,
                    startTime = now,
                ),
                now,
            )
        } else {
            return null
        }
        val next = transform(base.record).copy(
            diagnosticTimeline = append(base.record.diagnosticTimeline, event, MAX_TIMELINE_ITEMS),
        )
        active = ActiveSession(next, now)
        persist(next)
        next
    }

    private fun scheduleFinalization(
        sessionId: String,
        reason: FailureReason,
        delayMs: Long = FINALIZE_DELAY_MS,
    ) {
        scheduledFinalizers.remove(sessionId)?.cancel()
        scheduledFinalizers[sessionId] = appScope.launch {
            delay(delayMs)
            synchronized(lock) {
                val current = active?.takeIf { it.record.sessionId == sessionId } ?: return@synchronized
                if (current.record.success || !current.record.candidateSeen) return@synchronized
                val finalized = current.record.copy(
                    endTime = System.currentTimeMillis(),
                    finalFailureReason = reason.name,
                    diagnosticTimeline = append(current.record.diagnosticTimeline, "FINAL:$reason", MAX_TIMELINE_ITEMS),
                )
                active = ActiveSession(finalized, System.currentTimeMillis())
                persist(finalized, cleanup = true)
            }
            scheduledFinalizers.remove(sessionId)
        }
    }

    private fun persist(session: BypassDetectionSession, cleanup: Boolean = false) {
        appScope.launch(Dispatchers.IO) {
            persistMutex.withLock {
                DbSet.bypassDetectionSessionDao.upsert(session)
                if (cleanup) {
                    DbSet.bypassDetectionSessionDao.deleteBefore(System.currentTimeMillis() - RETENTION_MS)
                }
            }
        }
    }

    private fun append(value: String, item: String, limit: Int): String {
        val clean = item.replace(Regex("[^A-Za-z0-9_ .:/=-]"), "_").take(96)
        return (value.split('\n').filter { it.isNotBlank() } + clean).takeLast(limit).joinToString("\n")
    }

    private fun decodeSnapshots(value: String): List<BypassCandidateSnapshot> = runCatching {
        json.decodeFromString<List<BypassCandidateSnapshot>>(value)
    }.getOrDefault(emptyList())

    private fun encodeSnapshots(snapshots: List<BypassCandidateSnapshot>): String =
        json.encodeToString(snapshots.distinct().takeLast(MAX_SNAPSHOTS))
}

private object BypassCandidateSanitizer {
    private val closeTokens = listOf(
        "skip", "close", "dismiss", "cancel", "ignore", "ad_close",
        "跳过", "关闭", "略过", "广告", "倒计时", "×",
    )
    private val sensitiveTokens = listOf(
        "password", "passwd", "otp", "verification", "card", "bank",
        "密码", "验证码", "银行卡", "手机号", "电话", "聊天", "消息",
    )
    private val phoneOrCard = Regex("(?<!\\d)(?:\\d[ -]?){11,19}(?!\\d)")

    fun snapshot(
        node: AccessibilityNodeInfo,
        packageName: String,
        activityName: String?,
    ): BypassCandidateSnapshot? {
        val className = node.className?.toString().orEmpty()
        if (className.contains("edittext", ignoreCase = true) || node.isEditable || node.inputType != InputType.TYPE_NULL) {
            return null
        }
        val text = clean(node.text?.toString())
        val description = clean(node.contentDescription?.toString())
        val viewId = clean(node.viewIdResourceName)
        val values = listOfNotNull(text, description, viewId)
        if (values.isEmpty() || values.any(::isSensitive) || values.none(::isCloseLike)) return null
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return BypassCandidateSnapshot(
            text = text?.takeIf(::isCloseLike),
            description = description?.takeIf(::isCloseLike),
            viewId = viewId?.takeIf(::isCloseLike),
            className = className.take(120),
            bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
            clickable = node.isClickable,
            parentClickable = hasClickableParent(node),
            packageName = packageName,
            activityName = activityName,
        )
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }?.take(80)

    private fun isCloseLike(value: String): Boolean {
        val normalized = value.lowercase()
        return closeTokens.any { normalized.contains(it) }
    }

    private fun isSensitive(value: String): Boolean {
        val normalized = value.lowercase()
        return sensitiveTokens.any { normalized.contains(it) } || phoneOrCard.containsMatchIn(value)
    }

    private fun hasClickableParent(node: AccessibilityNodeInfo): Boolean {
        var parent = runCatching { node.parent }.getOrNull()
        repeat(5) {
            if (parent?.isClickable == true) return true
            parent = runCatching { parent?.parent }.getOrNull()
        }
        return false
    }
}

internal fun BypassDetectionSession.toFailureRecord(): BypassFailureRecord {
    val reason = runCatching { FailureReason.valueOf(finalFailureReason.orEmpty()) }
        .getOrDefault(FailureReason.UNKNOWN)
    val snapshots = runCatching {
        json.decodeFromString<List<BypassCandidateSnapshot>>(candidateSnapshots)
    }.getOrDefault(emptyList())
    val lines = diagnosticTimeline.lines().filter { it.isNotBlank() }
    val strategyLine = lines.lastOrNull { it.startsWith("STRATEGY:") }
    val candidateLines = lines.filter { it.startsWith("CLOSE_CANDIDATE_REJECTED:") || it.startsWith("ACTION_SUCCEEDED:") }
    val detail = buildString {
        strategyLine?.let { append(it.substringAfter("STRATEGY:")).append(" · ") }
        append(lines.takeLast(3).joinToString(" · "))
    }
    return BypassFailureRecord(
        id = sessionId,
        time = endTime.takeIf { it > 0 } ?: startTime,
        packageName = packageName,
        activityName = activityName,
        reason = reason,
        detail = detail.ifBlank { diagnosticTimeline.lines().takeLast(4).joinToString(" · ") },
        candidates = snapshots,
    )
}
