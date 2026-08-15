package app.bypassads.diagnostics

import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.SkipDecision

data class BlackBoxRecord(
    val epochMs: Long,
    val sessionId: Long,
    val scanIndex: Int,
    val packageName: String?,
    val windowCount: Int,
    val nodeCount: Int,
    val candidateFeatures: List<CandidateFeatures>,
    val decision: SkipDecision,
)
