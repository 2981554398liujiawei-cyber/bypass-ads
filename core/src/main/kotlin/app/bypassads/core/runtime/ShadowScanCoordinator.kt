package app.bypassads.core.runtime

/** A nullable package name must never stand in for either an idle or an unknown foreground. */
sealed interface PackageIdentity {
    data class Known(val packageName: String) : PackageIdentity
    data object Unknown : PackageIdentity
}

enum class ShadowCaseEndReason { COMPLETED, SUPERSEDED_BY_PACKAGE, MODE_OFF, SERVICE_DESTROYED, SCAN_BUSY, INTERNAL_ERROR }

data class ShadowCaseSnapshot(
    val generation: Long,
    val packageIdentity: PackageIdentity,
    val scheduledFrames: Int,
    val executedFrames: Int,
    val cancelledFrames: Int,
    val droppedFrames: Int,
)

sealed interface ShadowCoordinatorAction {
    data class Start(val generation: Long, val packageIdentity: PackageIdentity) : ShadowCoordinatorAction
    data class Coalesced(val packageIdentity: PackageIdentity) : ShadowCoordinatorAction
    data class End(val snapshot: ShadowCaseSnapshot, val reason: ShadowCaseEndReason) : ShadowCoordinatorAction
    data object Ignore : ShadowCoordinatorAction
}

/**
 * Serial coordinator used by the main-thread accessibility callback. It deliberately owns
 * generation invalidation, so a finished case can never regain permission to emit a scan.
 */
class ShadowScanCoordinator(private val burstFrames: Int = 6) {
    private var nextGeneration = 0L
    private var active: Active? = null
    private var workerBusy = false

    fun onForeground(identity: PackageIdentity, eligible: Boolean): ShadowCoordinatorAction {
        if (!eligible) return end(ShadowCaseEndReason.SUPERSEDED_BY_PACKAGE) ?: ShadowCoordinatorAction.Ignore
        val current = active
        if (current == null) return start(identity)
        when {
            current.identity is PackageIdentity.Known && identity is PackageIdentity.Unknown -> return ShadowCoordinatorAction.Coalesced(current.identity)
            current.identity is PackageIdentity.Unknown && identity is PackageIdentity.Known -> {
                current.identity = identity
                return ShadowCoordinatorAction.Coalesced(identity)
            }
            current.identity == identity -> return ShadowCoordinatorAction.Coalesced(identity)
            else -> {
                val ended = end(ShadowCaseEndReason.SUPERSEDED_BY_PACKAGE)!!
                // The caller receives the terminal record first, then invokes this again to start.
                pendingStart = identity
                return ended
            }
        }
    }

    private var pendingStart: PackageIdentity? = null
    fun startPending(): ShadowCoordinatorAction = pendingStart?.let { pendingStart = null; start(it) } ?: ShadowCoordinatorAction.Ignore

    fun start(identity: PackageIdentity): ShadowCoordinatorAction {
        val state = Active(++nextGeneration, identity, burstFrames)
        active = state
        return ShadowCoordinatorAction.Start(state.generation, identity)
    }

    fun tryStartFrame(generation: Long): Boolean {
        val state = active ?: return false
        if (state.generation != generation || state.closed) return false
        if (workerBusy) { state.droppedFrames++; state.pendingFrames--; return false }
        workerBusy = true
        state.executedFrames++
        state.pendingFrames--
        return true
    }

    /** Records an already-running request from an older generation. */
    fun markWorkerBusyForPreviousCase() { workerBusy = true }

    fun cancelFrame(generation: Long) { active?.takeIf { it.generation == generation && !it.closed }?.let { it.cancelledFrames++; it.pendingFrames-- } }
    fun canAppendScan(generation: Long): Boolean = active?.let { it.generation == generation && !it.closed } == true

    fun workerFinished(): ShadowCoordinatorAction? {
        workerBusy = false
        val state = active ?: return null
        return if (state.pendingFrames == 0) end(if (state.executedFrames == 0 && state.droppedFrames == state.scheduledFrames) ShadowCaseEndReason.SCAN_BUSY else ShadowCaseEndReason.COMPLETED) else null
    }

    fun end(reason: ShadowCaseEndReason): ShadowCoordinatorAction.End? {
        val state = active ?: return null
        state.closed = true // invalidate before exposing the terminal action
        active = null
        state.cancelledFrames += state.pendingFrames
        state.pendingFrames = 0
        return ShadowCoordinatorAction.End(state.snapshot(), reason)
    }

    private class Active(val generation: Long, var identity: PackageIdentity, val scheduledFrames: Int) {
        var pendingFrames = scheduledFrames
        var executedFrames = 0
        var cancelledFrames = 0
        var droppedFrames = 0
        var closed = false
        fun snapshot() = ShadowCaseSnapshot(generation, identity, scheduledFrames, executedFrames, cancelledFrames, droppedFrames)
    }
}
