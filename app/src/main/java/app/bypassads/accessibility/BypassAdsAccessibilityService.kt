package app.bypassads.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import app.bypassads.core.decision.DecisionEngine
import app.bypassads.core.detection.CandidateDetector
import app.bypassads.core.detection.TemporalCandidateTracker
import app.bypassads.core.runtime.BurstSchedulePolicy
import app.bypassads.core.runtime.EventPlan
import app.bypassads.core.runtime.ScanTrigger
import app.bypassads.core.runtime.PackageIdentity
import app.bypassads.diagnostics.BlackBoxRecord
import app.bypassads.diagnostics.BlackBoxStore
import app.bypassads.diagnostics.BlackBoxTrigger
import app.bypassads.diagnostics.CaseTerminationReason
import app.bypassads.runtime.RunMode
import app.bypassads.runtime.RunModeStore
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class BypassAdsAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val snapshotBuilder = NodeSnapshotBuilder()
    private val detector = CandidateDetector()
    private val temporalTracker = TemporalCandidateTracker()
    private val decisionEngine = DecisionEngine()
    private val sessionCounter = AtomicLong(0L)
    private val schedulePolicy = BurstSchedulePolicy()
    private val scheduledScans = mutableListOf<Runnable>()
    private val scanInFlight = AtomicBoolean(false)
    private val scanWorker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "bypass-ads-scan").apply { isDaemon = true } }

    private lateinit var runModeStore: RunModeStore
    private lateinit var blackBox: BlackBoxStore
    @Volatile private var activeSession = 0L
    private var pendingContentCallback: Runnable? = null
    private var lastObservedPackage: String? = null
    private var launcherPackage: String? = null
    private var modeObserver: Closeable? = null
    private var activeCase: ActiveCase? = null
    private var workerDroppedRequests = 0L
    private var workerMaxQueueDepth = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        runModeStore = RunModeStore(this)
        blackBox = BlackBoxStore(this)
        launcherPackage = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        modeObserver = runModeStore.observeModeChanges { mode -> if (mode == RunMode.OFF) handler.post(::disableRuntime) }
        runModeStore.markServiceConnected()
        blackBox.append(BlackBoxRecord(System.currentTimeMillis(), activeSession, scanIndex = -1, packageName = null, trigger = BlackBoxTrigger.SERVICE_CONNECTED))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::runModeStore.isInitialized) return
        val current = event ?: return
        val packageHint = current.packageName?.toString()
        val trigger = current.toScanTrigger(packageHint) ?: return
        // Excluded foregrounds still participate in transition tracking (A -> Home -> A).
        if (packageHint == packageName || packageHint == launcherPackage) {
            terminateActiveCase(CaseTerminationReason.SUPERSEDED_BY_PACKAGE)
            return
        }
        applyPlan(schedulePolicy.onEvent(runModeStore.get() != RunMode.OFF, trigger, packageHint.toPackageIdentity(), SystemClock.elapsedRealtime()))
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::blackBox.isInitialized) terminateActiveCase(CaseTerminationReason.SERVICE_DESTROYED)
        activeSession = sessionCounter.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        scanWorker.shutdownNow()
        modeObserver?.close()
        modeObserver = null
        super.onDestroy()
    }

    private fun disableRuntime() {
        terminateActiveCase(CaseTerminationReason.MODE_OFF)
        activeSession = sessionCounter.incrementAndGet()
        schedulePolicy.disable()
        cancelPendingContent()
        cancelScheduledScans()
        synchronized(temporalTracker) { temporalTracker.reset() }
    }

    private fun applyPlan(plan: EventPlan) {
        when (plan) {
            EventPlan.Ignore -> Unit
            is EventPlan.DebounceContent -> {
                if (plan.cancelExistingContent) cancelPendingContent()
                val callback = Runnable { pendingContentCallback = null; applyPlan(schedulePolicy.onContentDebounceElapsed(plan.packageIdentity, SystemClock.elapsedRealtime())) }
                pendingContentCallback = callback
                handler.postDelayed(callback, plan.delayMs)
            }
            is EventPlan.StartBurst -> {
                if (plan.cancelPendingContent) cancelPendingContent()
                if (plan.supersedesActiveBurst) terminateActiveCase(CaseTerminationReason.SUPERSEDED_BY_PACKAGE)
                startBurst(plan.packageIdentity.packageNameOrNull(), plan.trigger.toBlackBoxTrigger())
            }
            is EventPlan.CoalesceActive -> activeCase?.coalesce(plan.trigger)
        }
    }

    private fun cancelPendingContent() { pendingContentCallback?.let(handler::removeCallbacks); pendingContentCallback = null }
    private fun cancelScheduledScans(): Int { val cancelled = scheduledScans.size; scheduledScans.forEach(handler::removeCallbacks); scheduledScans.clear(); return cancelled }

    private fun startBurst(packageHint: String?, trigger: BlackBoxTrigger) {
        val session = sessionCounter.incrementAndGet()
        val caseId = UUID.randomUUID().toString()
        val startedAtMs = SystemClock.elapsedRealtime()
        activeSession = session
        synchronized(temporalTracker) { temporalTracker.reset() }
        schedulePolicy.markBurstStarted(packageHint.toPackageIdentity())
        activeCase = ActiveCase(session, caseId, packageHint, trigger, startedAtMs, BURST_DELAYS_MS.size)
        blackBox.append(BlackBoxRecord(System.currentTimeMillis(), session, caseId, -1, packageHint, trigger))
        BURST_DELAYS_MS.forEachIndexed { index, delay ->
            lateinit var callback: Runnable
            callback = Runnable {
                scheduledScans.remove(callback)
                val case = activeCase?.takeIf { it.sessionId == session } ?: return@Runnable
                if (!scanInFlight.compareAndSet(false, true)) { case.droppedFrames++; workerDroppedRequests++; maybeCompleteCase(); return@Runnable }
                case.executedFrames++
                workerMaxQueueDepth = 1
                val metrics = resources.displayMetrics
                try {
                    scanWorker.execute { scan(ScanRequest(session, caseId, index, packageHint, trigger, startedAtMs, metrics.widthPixels, metrics.heightPixels)) }
                } catch (_: RuntimeException) {
                    scanInFlight.set(false)
                    case.executedFrames--
                    case.cancelledFrames++
                    maybeCompleteCase()
                }
            }
            scheduledScans += callback
            handler.postDelayed(callback, delay)
        }
    }

    private fun maybeCompleteCase() {
        val case = activeCase ?: return
        if (scheduledScans.isEmpty() && !scanInFlight.get()) {
            val reason = if (case.executedFrames == 0 && case.droppedFrames == case.scheduledFrames) CaseTerminationReason.SCAN_BUSY else CaseTerminationReason.COMPLETED
            terminateActiveCase(reason)
        }
    }

    private fun terminateActiveCase(reason: CaseTerminationReason) {
        val case = activeCase ?: return
        // Invalidate before CASE_END so an old worker cannot append a post-end SCAN record.
        activeSession = sessionCounter.incrementAndGet()
        case.cancelledFrames += cancelScheduledScans()
        activeCase = null
        schedulePolicy.markBurstFinished()
        synchronized(temporalTracker) { temporalTracker.reset() }
        blackBox.append(BlackBoxRecord(System.currentTimeMillis(), case.sessionId, case.caseId, -1, case.packageName, BlackBoxTrigger.CASE_END, sourceTrigger = case.initialTrigger, scheduledFrames = case.scheduledFrames, executedFrames = case.executedFrames, cancelledFrames = case.cancelledFrames, droppedFrames = case.droppedFrames, coalescedWindowEvents = case.coalescedWindowEvents, coalescedContentEvents = case.coalescedContentEvents, caseDurationMs = SystemClock.elapsedRealtime() - case.startedAtElapsedMs, terminationReason = reason, workerBusyDrops = workerDroppedRequests, workerMaxQueueDepth = workerMaxQueueDepth))
    }

    private fun scan(request: ScanRequest) {
        val scanStartedAtMs = SystemClock.elapsedRealtime()
        try {
            if (request.session != activeSession || runModeStore.get() == RunMode.OFF) return
            val acquireStartedAtMs = SystemClock.elapsedRealtime()
            val currentWindows = windows.orEmpty()
            val acquireMs = SystemClock.elapsedRealtime() - acquireStartedAtMs
            val snapshotStartedAtMs = SystemClock.elapsedRealtime()
            val captured = snapshotBuilder.capture(currentWindows, request.packageHint, request.screenWidth, request.screenHeight)
            val snapshotMs = SystemClock.elapsedRealtime() - snapshotStartedAtMs
            val snapshot = captured.snapshot
            if (snapshot.packageName == packageName || snapshot.packageName == launcherPackage) return
            val detectionStartedAtMs = SystemClock.elapsedRealtime()
            val features = synchronized(temporalTracker) { temporalTracker.enrich(detector.extract(snapshot)) }
            val candidates = detector.score(features)
            val detectionMs = SystemClock.elapsedRealtime() - detectionStartedAtMs
            val decisionStartedAtMs = SystemClock.elapsedRealtime()
            val decision = decisionEngine.decide(candidates)
            val decisionMs = SystemClock.elapsedRealtime() - decisionStartedAtMs
            if (request.session != activeSession || runModeStore.get() == RunMode.OFF) return
            blackBox.append(BlackBoxRecord(System.currentTimeMillis(), request.session, request.caseId, request.scanIndex, snapshot.packageName, BlackBoxTrigger.SCAN, request.sourceTrigger, snapshot.windowCount, snapshot.nodes.size, features, decision, SystemClock.elapsedRealtime() - request.triggeredAtElapsedMs, SystemClock.elapsedRealtime() - scanStartedAtMs, windowAcquireMs = acquireMs, snapshotMs = snapshotMs, detectionMs = detectionMs, decisionMs = decisionMs, windowsTraversed = captured.metrics.windowsTraversed, nodesVisited = captured.metrics.nodesVisited, maxDepth = captured.metrics.maxDepth, rootAcquireMaxMs = captured.metrics.rootAcquireMaxMs, childQueryMaxMs = captured.metrics.childQueryMaxMs, captureBudgetHit = captured.metrics.budgetHit, captureBudgetReason = captured.metrics.budgetReason, workerBusyDrops = workerDroppedRequests, workerMaxQueueDepth = workerMaxQueueDepth))
        } catch (_: Exception) {
            handler.post { activeCase?.takeIf { it.sessionId == request.session }?.let { terminateActiveCase(CaseTerminationReason.INTERNAL_ERROR) } }
        } finally {
            scanInFlight.set(false)
            handler.post(::maybeCompleteCase)
        }
    }

    private fun AccessibilityEvent.toScanTrigger(packageHint: String?): ScanTrigger? = when (eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> { val changed = packageHint != null && packageHint != lastObservedPackage; lastObservedPackage = packageHint ?: lastObservedPackage; if (changed) ScanTrigger.PACKAGE_CHANGED else ScanTrigger.WINDOW_STATE_CHANGED }
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> ScanTrigger.WINDOWS_CHANGED
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> ScanTrigger.CONTENT_CHANGED
        else -> null
    }
    private fun ScanTrigger.toBlackBoxTrigger(): BlackBoxTrigger = when (this) {
        ScanTrigger.WINDOW_STATE_CHANGED -> BlackBoxTrigger.WINDOW_STATE_CHANGED; ScanTrigger.WINDOWS_CHANGED -> BlackBoxTrigger.WINDOWS_CHANGED; ScanTrigger.CONTENT_CHANGED -> BlackBoxTrigger.CONTENT_CHANGED; ScanTrigger.PACKAGE_CHANGED -> BlackBoxTrigger.PACKAGE_CHANGED
    }
    private fun String?.toPackageIdentity(): PackageIdentity = this?.let(PackageIdentity::Known) ?: PackageIdentity.Unknown
    private fun PackageIdentity.packageNameOrNull(): String? = (this as? PackageIdentity.Known)?.packageName
    private data class ScanRequest(val session: Long, val caseId: String, val scanIndex: Int, val packageHint: String?, val sourceTrigger: BlackBoxTrigger, val triggeredAtElapsedMs: Long, val screenWidth: Int, val screenHeight: Int)
    private class ActiveCase(val sessionId: Long, val caseId: String, val packageName: String?, val initialTrigger: BlackBoxTrigger, val startedAtElapsedMs: Long, val scheduledFrames: Int, var executedFrames: Int = 0, var cancelledFrames: Int = 0, var droppedFrames: Int = 0, var coalescedWindowEvents: Int = 0, var coalescedContentEvents: Int = 0) { fun coalesce(trigger: ScanTrigger) { if (trigger == ScanTrigger.CONTENT_CHANGED) coalescedContentEvents++ else coalescedWindowEvents++ } }
    private companion object { val BURST_DELAYS_MS = longArrayOf(0L, 60L, 140L, 300L, 600L, 1_000L) }
}
