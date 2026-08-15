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
        val packageIdentity: PackageIdentity,
        val delayMs: Long,
        val cancelExistingContent: Boolean,
    ) : EventPlan

    data class StartBurst(
        val packageIdentity: PackageIdentity,
        val trigger: ScanTrigger,
        val cancelPendingContent: Boolean,
        val supersedesActiveBurst: Boolean,
    ) : EventPlan

    data class CoalesceActive(val trigger: ScanTrigger) : EventPlan
}

/** Pure scheduling policy so Android callback timing stays small and testable. */
class BurstSchedulePolicy(
    private val contentDebounceMs: Long = CONTENT_DEBOUNCE_MS,
    private val contentMaxWaitMs: Long = CONTENT_MAX_WAIT_MS,
) {
    private var pendingContent: PendingContent? = null
    private var activePackage: PackageIdentity? = null

    fun onEvent(enabled: Boolean, trigger: ScanTrigger, packageIdentity: PackageIdentity, nowMs: Long): EventPlan {
        if (!enabled) return EventPlan.Ignore
        if (activePackage != null) {
            if (activePackage is PackageIdentity.Unknown && packageIdentity is PackageIdentity.Known) {
                activePackage = packageIdentity
                return EventPlan.CoalesceActive(trigger)
            }
            if (activePackage == packageIdentity || packageIdentity is PackageIdentity.Unknown) return EventPlan.CoalesceActive(trigger)
            val hadPendingContent = pendingContent != null
            pendingContent = null
            activePackage = null
            return EventPlan.StartBurst(packageIdentity, trigger, hadPendingContent, supersedesActiveBurst = true)
        }

        if (trigger != ScanTrigger.CONTENT_CHANGED) {
            val hadPendingContent = pendingContent != null
            pendingContent = null
            return EventPlan.StartBurst(packageIdentity, trigger, hadPendingContent, supersedesActiveBurst = false)
        }

        val previous = pendingContent
        val firstAtMs = previous?.takeIf { it.packageIdentity == packageIdentity }?.firstAtMs ?: nowMs
        val dueAtMs = minOf(nowMs + contentDebounceMs, firstAtMs + contentMaxWaitMs)
        pendingContent = PendingContent(packageIdentity, firstAtMs, dueAtMs)
        return EventPlan.DebounceContent(
            packageIdentity = packageIdentity,
            delayMs = dueAtMs - nowMs,
            cancelExistingContent = previous != null,
        )
    }

    fun onContentDebounceElapsed(packageIdentity: PackageIdentity, nowMs: Long): EventPlan {
        val pending = pendingContent ?: return EventPlan.Ignore
        if (pending.packageIdentity != packageIdentity || nowMs < pending.dueAtMs) return EventPlan.Ignore
        pendingContent = null
        return if (activePackage == packageIdentity || packageIdentity is PackageIdentity.Unknown) {
            EventPlan.CoalesceActive(ScanTrigger.CONTENT_CHANGED)
        } else {
            EventPlan.StartBurst(packageIdentity, ScanTrigger.CONTENT_CHANGED, cancelPendingContent = false, supersedesActiveBurst = activePackage != null)
        }
    }

    fun markBurstStarted(packageIdentity: PackageIdentity) {
        activePackage = packageIdentity
    }

    fun markBurstFinished() {
        activePackage = null
    }

    fun disable() {
        pendingContent = null
        activePackage = null
    }

    private data class PendingContent(val packageIdentity: PackageIdentity, val firstAtMs: Long, val dueAtMs: Long)

    companion object {
        const val CONTENT_DEBOUNCE_MS = 250L
        const val CONTENT_MAX_WAIT_MS = 1_000L
    }
}
