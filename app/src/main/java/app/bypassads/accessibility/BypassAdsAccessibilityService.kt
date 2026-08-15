package app.bypassads.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import app.bypassads.core.decision.DecisionEngine
import app.bypassads.core.detection.CandidateDetector
import app.bypassads.core.detection.TemporalCandidateTracker
import app.bypassads.diagnostics.BlackBoxRecord
import app.bypassads.diagnostics.BlackBoxStore
import app.bypassads.diagnostics.BlackBoxTrigger
import app.bypassads.core.runtime.BurstSchedulePolicy
import app.bypassads.core.runtime.EventPlan
import app.bypassads.core.runtime.ScanTrigger
import app.bypassads.runtime.RunMode
import app.bypassads.runtime.RunModeStore
import java.io.Closeable
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

    private lateinit var runModeStore: RunModeStore
    private lateinit var blackBox: BlackBoxStore
    private var activeSession = 0L
    private var pendingContentCallback: Runnable? = null
    private var lastObservedPackage: String? = null
    private var modeObserver: Closeable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        runModeStore = RunModeStore(this)
        blackBox = BlackBoxStore(this)
        modeObserver = runModeStore.observeModeChanges { mode ->
            if (mode == RunMode.OFF) {
                if (Looper.myLooper() == Looper.getMainLooper()) disableRuntime() else handler.post(::disableRuntime)
            }
        }
        runModeStore.markServiceConnected()
        blackBox.append(
            BlackBoxRecord(
                epochMs = System.currentTimeMillis(),
                sessionId = activeSession,
                scanIndex = -1,
                packageName = null,
                trigger = BlackBoxTrigger.SERVICE_CONNECTED,
            ),
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::runModeStore.isInitialized) return
        val current = event ?: return
        val packageHint = current.packageName?.toString()
        if (packageHint == packageName) return
        val trigger = current.toScanTrigger(packageHint) ?: return
        applyPlan(schedulePolicy.onEvent(runModeStore.get() != RunMode.OFF, trigger, packageHint, SystemClock.elapsedRealtime()))
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        activeSession = sessionCounter.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        modeObserver?.close()
        modeObserver = null
        super.onDestroy()
    }

    private fun disableRuntime() {
        activeSession = sessionCounter.incrementAndGet()
        schedulePolicy.disable()
        cancelPendingContent()
        cancelScheduledScans()
        temporalTracker.reset()
    }

    private fun applyPlan(plan: EventPlan) {
        when (plan) {
            EventPlan.Ignore -> Unit
            is EventPlan.DebounceContent -> {
                if (plan.cancelExistingContent) cancelPendingContent()
                val callback = Runnable {
                    pendingContentCallback = null
                    applyPlan(schedulePolicy.onContentDebounceElapsed(plan.packageName, SystemClock.elapsedRealtime()))
                }
                pendingContentCallback = callback
                handler.postDelayed(callback, plan.delayMs)
            }
            is EventPlan.StartBurst -> {
                if (plan.cancelPendingContent) cancelPendingContent()
                if (plan.cancelActiveBurst) cancelScheduledScans()
                startBurst(plan.packageName, plan.trigger.toBlackBoxTrigger())
            }
        }
    }

    private fun cancelPendingContent() {
        pendingContentCallback?.let(handler::removeCallbacks)
        pendingContentCallback = null
    }

    private fun cancelScheduledScans() {
        scheduledScans.forEach(handler::removeCallbacks)
        scheduledScans.clear()
    }

    private fun startBurst(packageHint: String?, trigger: BlackBoxTrigger) {
        val session = sessionCounter.incrementAndGet()
        val triggeredAtElapsedMs = SystemClock.elapsedRealtime()
        activeSession = session
        temporalTracker.reset()
        blackBox.append(
            BlackBoxRecord(
                epochMs = System.currentTimeMillis(),
                sessionId = session,
                scanIndex = -1,
                packageName = packageHint,
                trigger = trigger,
            ),
        )
        BURST_DELAYS_MS.forEachIndexed { index, delay ->
            lateinit var callback: Runnable
            callback = Runnable {
                scheduledScans.remove(callback)
                scan(session, index, packageHint, trigger, triggeredAtElapsedMs)
            }
            scheduledScans += callback
            handler.postDelayed(callback, delay)
        }
    }

    private fun scan(
        session: Long,
        scanIndex: Int,
        packageHint: String?,
        sourceTrigger: BlackBoxTrigger,
        triggeredAtElapsedMs: Long,
    ) {
        if (session != activeSession || runModeStore.get() == RunMode.OFF) return
        val scanStartedAtElapsedMs = SystemClock.elapsedRealtime()
        val metrics = resources.displayMetrics
        val snapshot = snapshotBuilder.capture(
            windows = windows.orEmpty(),
            packageHint = packageHint,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            elapsedMs = SystemClock.elapsedRealtime(),
        )
        if (snapshot.packageName == packageName) return

        val features = temporalTracker.enrich(detector.extract(snapshot))
        val candidates = detector.score(features)
        val decision = decisionEngine.decide(candidates)
        blackBox.append(
            BlackBoxRecord(
                epochMs = System.currentTimeMillis(),
                sessionId = session,
                scanIndex = scanIndex,
                packageName = snapshot.packageName,
                trigger = BlackBoxTrigger.SCAN,
                sourceTrigger = sourceTrigger,
                windowCount = snapshot.windowCount,
                nodeCount = snapshot.nodes.size,
                candidateFeatures = features,
                decision = decision,
                latencyMs = SystemClock.elapsedRealtime() - triggeredAtElapsedMs,
                scanWorkMs = SystemClock.elapsedRealtime() - scanStartedAtElapsedMs,
            ),
        )

        // M1 safety gate: Shadow mode only. This service never invokes click or gesture APIs.
    }

    private fun AccessibilityEvent.toScanTrigger(packageHint: String?): ScanTrigger? = when (eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
            val changed = packageHint != null && packageHint != lastObservedPackage
            lastObservedPackage = packageHint ?: lastObservedPackage
            if (changed) ScanTrigger.PACKAGE_CHANGED else ScanTrigger.WINDOW_STATE_CHANGED
        }
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> ScanTrigger.WINDOWS_CHANGED
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> ScanTrigger.CONTENT_CHANGED
        else -> null
    }

    private fun ScanTrigger.toBlackBoxTrigger(): BlackBoxTrigger = when (this) {
        ScanTrigger.WINDOW_STATE_CHANGED -> BlackBoxTrigger.WINDOW_STATE_CHANGED
        ScanTrigger.WINDOWS_CHANGED -> BlackBoxTrigger.WINDOWS_CHANGED
        ScanTrigger.CONTENT_CHANGED -> BlackBoxTrigger.CONTENT_CHANGED
        ScanTrigger.PACKAGE_CHANGED -> BlackBoxTrigger.PACKAGE_CHANGED
    }

    private companion object {
        val BURST_DELAYS_MS = longArrayOf(0L, 60L, 140L, 300L, 600L, 1_000L)
    }
}
