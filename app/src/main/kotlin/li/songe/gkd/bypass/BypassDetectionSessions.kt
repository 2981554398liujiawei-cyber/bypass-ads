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
 * One ad session is one ad. Matcher evidence is accumulated into the active
 * window session; multiple actions (skip -> X -> close) stay in ONE session
 * and produce exactly one [BypassSessionResult].
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

    /**
     * Get (or create) the active session for the current window. The session
     * carries the strategy mode at session start and the rule trust origin.
     * Returns null only when a session cannot be established (no window).
     */
    fun ensureSession(
        packageName: String,
        activityName: String?,
        mode: BypassAdStrategyMode,
        ruleOrigin: BypassRuleTrust,
    ): String? {
        val session = update(packageName, activityName, "SESSION_OPEN", create = true) { record ->
            if (record.result == BypassSessionResult.OPEN.name) {
                record.copy(
                    strategyMode = record.strategyMode.takeIf { it != BypassDetectionSession.BypassStrategyModeDefault } ?: mode.name,
                    ruleOrigin = record.ruleOrigin ?: ruleOrigin.name,
                )
            } else {
                record
            }
        }
        return session?.sessionId
    }

    fun noteCandidate(
        sessionId: String,
        packageName: String,
        activityName: String?,
        candidateType: BypassExitCandidateType,
        ruleLabel: String,
        target: AccessibilityNodeInfo,
    ) {
        val snapshot = BypassCandidateSanitizer.snapshot(target, packageName, activityName, structural = true)
        updateById(sessionId, "TARGET_FOUND:${candidateType.name}") { record ->
            record.copy(
                candidateSeen = true,
                candidateType = candidateType.name,
                matchedRules = append(record.matchedRules, ruleLabel, MAX_RULES),
                candidateSnapshots = snapshot?.let {
                    encodeSnapshots(decodeSnapshots(record.candidateSnapshots) + it)
                } ?: record.candidateSnapshots,
            )
        }
    }

    /** Record that one action attempt was reserved/performed. */
    fun actionAttempted(sessionId: String, action: String) {
        updateById(sessionId, "ACTION_ATTEMPT:${action.take(48)}") { record ->
            record.copy(
                actions = append(record.actions, action, MAX_ACTIONS),
                actionAttempts = record.actionAttempts + 1,
            )
        }
    }

    /** Record the verified outcome of the session's actions. */
    fun outcomeConfirmed(sessionId: String, outcome: BypassOutcome, latencyMs: Long) {
        val result = when (outcome) {
            BypassOutcome.SUCCESS_CONFIRMED -> BypassSessionResult.SUCCESS_CONFIRMED
            BypassOutcome.ACTION_NO_EFFECT -> BypassSessionResult.UNRESOLVED
            BypassOutcome.UNRESOLVED -> BypassSessionResult.UNRESOLVED
            BypassOutcome.MISCLICK_SUSPECTED -> BypassSessionResult.MISCLICK_SUSPECTED
        }
        val now = System.currentTimeMillis()
        updateById(sessionId, "OUTCOME:${result.name}:${latencyMs}ms") { record ->
            record.copy(
                result = result.name,
                success = result == BypassSessionResult.SUCCESS_CONFIRMED,
                endTime = now,
                confirmedLatencyMs = latencyMs,
                finalFailureReason = when (result) {
                    BypassSessionResult.MISCLICK_SUSPECTED -> FailureReason.MISCLICK_SUSPECTED.name
                    else -> record.finalFailureReason
                },
            )
        }?.let {
            scheduledFinalizers.remove(sessionId)?.cancel()
            if (result == BypassSessionResult.SUCCESS_CONFIRMED || result == BypassSessionResult.MISCLICK_SUSPECTED) {
                finalizeNow(sessionId)
            }
        }
    }

    /** The active session could not be resolved within the window. */
    fun unresolved(sessionId: String, detail: String) {
        val now = System.currentTimeMillis()
        updateById(sessionId, "UNRESOLVED:$detail") { record ->
            record.copy(
                result = BypassSessionResult.UNRESOLVED.name,
                endTime = now,
            )
        }
    }

    /** Finalize a session as a confirmed failure (budget exhausted). */
    fun confirmedFailure(sessionId: String, reason: FailureReason) {
        val now = System.currentTimeMillis()
        updateById(sessionId, "FINAL:$reason") { record ->
            record.copy(
                result = BypassSessionResult.FAILURE_CONFIRMED.name,
                success = false,
                endTime = now,
                finalFailureReason = reason.name,
            )
        }?.let {
            scheduledFinalizers.remove(sessionId)?.cancel()
            finalizeNow(sessionId)
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

    private fun finalizeNow(sessionId: String) {
        synchronized(lock) {
            val current = active?.takeIf { it.record.sessionId == sessionId } ?: return
            persist(current.record, cleanup = true)
            active = null
        }
        BypassActionBudget.reset(sessionId)
    }

    private fun updateById(
        sessionId: String,
        event: String,
        transform: (BypassDetectionSession) -> BypassDetectionSession = { it },
    ): BypassDetectionSession? = synchronized(lock) {
        val current = active?.takeIf { it.record.sessionId == sessionId } ?: return null
        val next = transform(current.record).copy(
            diagnosticTimeline = append(current.record.diagnosticTimeline, event, MAX_TIMELINE_ITEMS),
        )
        active = ActiveSession(next, System.currentTimeMillis())
        persist(next)
        next
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
                // closed instead of being kept alive forever.
                now - it.record.startTime <= SESSION_HARD_TIMEOUT_MS
        }
        if (matching == null && current != null) {
            // A context change ends a partial session without presenting it as
            // a failure. Evidence alone is not enough to accuse a missed ad.
            persist(current.record.copy(endTime = now))
            BypassActionBudget.reset(current.record.sessionId)
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
                    result = BypassSessionResult.FAILURE_CONFIRMED.name,
                    finalFailureReason = reason.name,
                    diagnosticTimeline = append(current.record.diagnosticTimeline, "FINAL:$reason", MAX_TIMELINE_ITEMS),
                )
                // Terminal: a FAILURE_CONFIRMED session must never be reused
                // as the active session by a later update (P1). The next
                // window opens a brand-new session instead.
                persist(finalized, cleanup = true)
                active = null
            }
            scheduledFinalizers.remove(sessionId)
            BypassActionBudget.reset(sessionId)
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

/** Converts a session into a product record (success or failure row). */
internal fun BypassDetectionSession.toSessionRecord(): BypassSessionRecord {
    val reason = runCatching { FailureReason.valueOf(finalFailureReason.orEmpty()) }
        .getOrDefault(FailureReason.UNKNOWN)
    val snapshots = runCatching {
        json.decodeFromString<List<BypassCandidateSnapshot>>(candidateSnapshots)
    }.getOrDefault(emptyList())
    return BypassSessionRecord(
        id = sessionId,
        time = endTime.takeIf { it > 0 } ?: startTime,
        packageName = packageName,
        activityName = activityName,
        strategyMode = runCatching { BypassAdStrategyMode.valueOf(strategyMode) }.getOrDefault(BypassAdStrategyMode.CONSERVATIVE),
        result = sessionResult,
        actionAttempts = actionAttempts,
        confirmedLatencyMs = confirmedLatencyMs,
        candidateType = candidateType?.let { runCatching { BypassExitCandidateType.valueOf(it) }.getOrNull() },
        reason = reason,
        actions = actions.lines().filter { it.isNotBlank() },
        candidates = snapshots,
        ruleOrigin = ruleOrigin,
    )
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

    /**
     * Snapshot a candidate. With [structural] a text-less small node inside
     * an ad window is also snapshotted (class/bounds/clickable only) so
     * CRAZY structural failures can be taught without page content.
     */
    fun snapshot(
        node: AccessibilityNodeInfo,
        packageName: String,
        activityName: String?,
        structural: Boolean,
    ): BypassCandidateSnapshot? {
        val className = node.className?.toString().orEmpty()
        if (className.contains("edittext", ignoreCase = true) || node.isEditable || node.inputType != InputType.TYPE_NULL) {
            return null
        }
        val text = clean(node.text?.toString())
        val description = clean(node.contentDescription?.toString())
        val viewId = clean(node.viewIdResourceName)
        val values = listOfNotNull(text, description, viewId)
        val sensitive = values.any(::isSensitive)
        if (sensitive) return null
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val small = bounds.width() in 1..220 && bounds.height() in 1..160
        val looksLikeClose = values.any(::isCloseLike) || (structural && small && !node.isEditable)
        if (!looksLikeClose) return null
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
