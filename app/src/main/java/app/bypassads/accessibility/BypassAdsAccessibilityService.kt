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
import app.bypassads.runtime.RunMode
import app.bypassads.runtime.RunModeStore
import java.util.concurrent.atomic.AtomicLong

class BypassAdsAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val snapshotBuilder = NodeSnapshotBuilder()
    private val detector = CandidateDetector()
    private val temporalTracker = TemporalCandidateTracker()
    private val decisionEngine = DecisionEngine()
    private val sessionCounter = AtomicLong(0L)

    private lateinit var runModeStore: RunModeStore
    private lateinit var blackBox: BlackBoxStore
    private var activeSession = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        runModeStore = RunModeStore(this)
        blackBox = BlackBoxStore(this)
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
        if (!::runModeStore.isInitialized || runModeStore.get() == RunMode.OFF) return
        val current = event ?: return
        val trigger = current.toBlackBoxTrigger() ?: return
        val packageHint = current.packageName?.toString()
        if (packageHint == packageName) return

        startBurst(packageHint, trigger)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        activeSession = sessionCounter.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
            handler.postDelayed({ scan(session, index, packageHint, trigger, triggeredAtElapsedMs) }, delay)
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
            ),
        )

        // M1 safety gate: Shadow mode only. This service never invokes click or gesture APIs.
    }

    private fun AccessibilityEvent.toBlackBoxTrigger(): BlackBoxTrigger? = when (eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> BlackBoxTrigger.PACKAGE_CHANGED
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> BlackBoxTrigger.WINDOW_CHANGED
        else -> null
    }

    private companion object {
        val BURST_DELAYS_MS = longArrayOf(0L, 60L, 140L, 300L, 600L, 1_000L)
    }
}
