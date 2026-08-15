package app.lttnext.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import app.lttnext.core.decision.DecisionEngine
import app.lttnext.core.detection.CandidateDetector
import app.lttnext.core.detection.TemporalCandidateTracker
import app.lttnext.diagnostics.BlackBoxRecord
import app.lttnext.diagnostics.BlackBoxStore
import app.lttnext.runtime.RunMode
import app.lttnext.runtime.RunModeStore
import java.util.concurrent.atomic.AtomicLong

class LttAccessibilityService : AccessibilityService() {
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::runModeStore.isInitialized || runModeStore.get() == RunMode.OFF) return
        val current = event ?: return
        if (current.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            current.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            startBurst(current.packageName?.toString())
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        activeSession = sessionCounter.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startBurst(packageHint: String?) {
        if (packageHint == packageName) return
        val session = sessionCounter.incrementAndGet()
        activeSession = session
        temporalTracker.reset()
        BURST_DELAYS_MS.forEachIndexed { index, delay ->
            handler.postDelayed({ scan(session, index, packageHint) }, delay)
        }
    }

    private fun scan(session: Long, scanIndex: Int, packageHint: String?) {
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
                windowCount = snapshot.windowCount,
                nodeCount = snapshot.nodes.size,
                candidateFeatures = features,
                decision = decision,
            ),
        )

        // v0.1 safety gate: no real actuation even if RunMode.ACTIVE is set internally.
    }

    private companion object {
        val BURST_DELAYS_MS = longArrayOf(0L, 60L, 140L, 300L, 600L, 1_000L)
    }
}
