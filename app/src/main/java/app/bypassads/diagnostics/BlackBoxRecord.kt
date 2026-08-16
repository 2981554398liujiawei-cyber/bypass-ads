package app.bypassads.diagnostics

import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.SkipDecision

enum class BlackBoxTrigger {
    SERVICE_CONNECTED,
    PACKAGE_CHANGED,
    WINDOW_CHANGED,
    WINDOW_STATE_CHANGED,
    WINDOWS_CHANGED,
    CONTENT_CHANGED,
    SCAN,
    CASE_END,
    /** M2.2: experimental actuation attempt (guard verdict / gate verdict / outcome). */
    ACTION_ATTEMPT,
    /** M2.2 P1: passive post-action outcome verification (target/window observed after the attempt window). */
    OUTCOME_VERIFIED,
}

enum class CaseTerminationReason {
    COMPLETED,
    SUPERSEDED_BY_PACKAGE,
    MODE_OFF,
    SERVICE_DESTROYED,
    SCAN_BUSY,
    INTERNAL_ERROR,
}

data class BlackBoxRecord(
    val epochMs: Long,
    val sessionId: Long,
    val caseId: String? = null,
    val scanIndex: Int,
    val packageName: String?,
    val trigger: BlackBoxTrigger,
    val sourceTrigger: BlackBoxTrigger? = null,
    val windowCount: Int = 0,
    val nodeCount: Int = 0,
    val candidateFeatures: List<CandidateFeatures> = emptyList(),
    val decision: SkipDecision? = null,
    val latencyMs: Long? = null,
    val scanWorkMs: Long? = null,
    val windowAcquireMs: Long? = null,
    val snapshotMs: Long? = null,
    val detectionMs: Long? = null,
    val decisionMs: Long? = null,
    val windowsTraversed: Int? = null,
    val nodesVisited: Int? = null,
    val maxDepth: Int? = null,
    val rootAcquireMaxMs: Long? = null,
    val childQueryMaxMs: Long? = null,
    val captureBudgetHit: Boolean? = null,
    val captureBudgetReason: String? = null,
    val workerBusyDrops: Long? = null,
    val workerMaxQueueDepth: Int? = null,
    val scheduledFrames: Int? = null,
    val executedFrames: Int? = null,
    val cancelledFrames: Int? = null,
    val droppedFrames: Int? = null,
    val coalescedWindowEvents: Int? = null,
    val coalescedContentEvents: Int? = null,
    val caseDurationMs: Long? = null,
    val terminationReason: CaseTerminationReason? = null,
    /** M2.2: actuation gate verdict, e.g. "ALLOW", "BLOCK:CTA_RISK", "GUARD:KILL_SWITCH_OFF". */
    val actuationVerdict: String? = null,
    /** M2.2: post-action outcome, e.g. "SUCCESS", "NO_EFFECT", "UNCERTAIN". */
    val actuationOutcome: String? = null,
    /** M2.2 P1: raw performAction boolean of the last dispatched click; diagnostic only, not used for outcome. */
    val actuationDispatchReported: Boolean? = null,
    /** M2.3: free-form execution diagnostic (e.g. fast-path action-target capability chain). */
    val actuationDiagnostics: String? = null,
)

data class DiagnosticReason(
    val code: String,
    val points: Int,
)

data class DiagnosticCandidate(
    val label: String,
    val resourceId: String?,
    val bounds: List<Int>,
    val clickable: Boolean,
    val stabilityHits: Int,
    val countdownValue: Int?,
    val countdownStepObserved: Boolean,
    val positionDriftDetected: Boolean,
    val ctaSiblingDetected: Boolean,
    val identityAmbiguous: Boolean,
)

data class DiagnosticRecord(
    val epochMs: Long,
    val sessionId: Long,
    val caseId: String?,
    val scanIndex: Int,
    val packageName: String?,
    val trigger: BlackBoxTrigger,
    val sourceTrigger: BlackBoxTrigger?,
    val windowCount: Int,
    val nodeCount: Int,
    val candidateCount: Int,
    val decision: String?,
    val rejectionReason: String?,
    val score: Int?,
    val evidence: List<DiagnosticReason>,
    val risks: List<DiagnosticReason>,
    val candidates: List<DiagnosticCandidate>,
    val latencyMs: Long?,
    val scanWorkMs: Long?,
    val windowAcquireMs: Long?,
    val snapshotMs: Long?,
    val detectionMs: Long?,
    val decisionMs: Long?,
    val windowsTraversed: Int?,
    val nodesVisited: Int?,
    val maxDepth: Int?,
    val rootAcquireMaxMs: Long?,
    val childQueryMaxMs: Long?,
    val captureBudgetHit: Boolean?,
    val captureBudgetReason: String?,
    val workerBusyDrops: Long?,
    val workerMaxQueueDepth: Int?,
    val scheduledFrames: Int?,
    val executedFrames: Int?,
    val cancelledFrames: Int?,
    val droppedFrames: Int?,
    val coalescedWindowEvents: Int?,
    val coalescedContentEvents: Int?,
    val caseDurationMs: Long?,
    val terminationReason: CaseTerminationReason?,
    val actuationVerdict: String?,
    val actuationOutcome: String?,
    val actuationDispatchReported: Boolean? = null,
    val actuationDiagnostics: String? = null,
)

data class BlackBoxStats(
    val recordCount: Int,
    val wouldClickCount: Int,
    val rejectedCount: Int,
    val latestDecision: DiagnosticRecord?,
)
