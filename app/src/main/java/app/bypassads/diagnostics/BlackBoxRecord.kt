package app.bypassads.diagnostics

import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.SkipDecision

enum class BlackBoxTrigger {
    SERVICE_CONNECTED,
    PACKAGE_CHANGED,
    WINDOW_CHANGED,
    SCAN,
}

data class BlackBoxRecord(
    val epochMs: Long,
    val sessionId: Long,
    val scanIndex: Int,
    val packageName: String?,
    val trigger: BlackBoxTrigger,
    val sourceTrigger: BlackBoxTrigger? = null,
    val windowCount: Int = 0,
    val nodeCount: Int = 0,
    val candidateFeatures: List<CandidateFeatures> = emptyList(),
    val decision: SkipDecision? = null,
    val latencyMs: Long? = null,
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
)

data class DiagnosticRecord(
    val epochMs: Long,
    val sessionId: Long,
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
)

data class BlackBoxStats(
    val recordCount: Int,
    val wouldClickCount: Int,
    val rejectedCount: Int,
    val latestDecision: DiagnosticRecord?,
)
