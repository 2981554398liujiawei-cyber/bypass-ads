package app.bypassads.core.runtime

enum class ScanTrigger {
    WINDOW_STATE_CHANGED,
    WINDOWS_CHANGED,
    CONTENT_CHANGED,
    PACKAGE_CHANGED,
}

sealed interface EventPlan {
    data object Ignore : EventPlan
    data class DebounceContent(
        val packageName: String?,
        val delayMs: Long,
        val cancelExistingContent: Boolean,
    ) : EventPlan

    data class StartBurst(
        val packageName: String?,
        val trigger: ScanTrigger,
        val cancelPendingContent: Boolean,
        val cancelActiveBurst: Boolean,
    ) : EventPlan
}

/** Pure scheduling policy so Android callback timing stays small and testable. */
class BurstSchedulePolicy(private val contentDebounceMs: Long = CONTENT_DEBOUNCE_MS) {
    private var pendingContent: PendingContent? = null

    fun onEvent(enabled: Boolean, trigger: ScanTrigger, packageName: String?, nowMs: Long): EventPlan {
        if (!enabled) return EventPlan.Ignore
        if (trigger != ScanTrigger.CONTENT_CHANGED) {
            val hadPendingContent = pendingContent != null
            pendingContent = null
            return EventPlan.StartBurst(packageName, trigger, hadPendingContent, cancelActiveBurst = true)
        }

        val previous = pendingContent
        pendingContent = PendingContent(packageName, nowMs + contentDebounceMs)
        return EventPlan.DebounceContent(
            packageName = packageName,
            delayMs = contentDebounceMs,
            cancelExistingContent = previous != null,
        )
    }

    fun onContentDebounceElapsed(packageName: String?, nowMs: Long): EventPlan {
        val pending = pendingContent ?: return EventPlan.Ignore
        if (pending.packageName != packageName || nowMs < pending.dueAtMs) return EventPlan.Ignore
        pendingContent = null
        return EventPlan.StartBurst(packageName, ScanTrigger.CONTENT_CHANGED, cancelPendingContent = false, cancelActiveBurst = true)
    }

    fun disable() {
        pendingContent = null
    }

    private data class PendingContent(val packageName: String?, val dueAtMs: Long)

    companion object {
        const val CONTENT_DEBOUNCE_MS = 250L
    }
}
