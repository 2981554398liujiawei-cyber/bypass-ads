package app.lttnext.diagnostics

import app.lttnext.core.model.CandidateFeatures
import app.lttnext.core.model.SkipDecision

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
