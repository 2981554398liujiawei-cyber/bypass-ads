package app.bypassads.core.runtime

data class CaseTraceEntry(
    val caseId: String,
    val sessionId: Long,
    val scanIndex: Int,
    val isScan: Boolean,
    val isEnd: Boolean,
    val scheduled: Int? = null,
    val executed: Int? = null,
    val cancelled: Int? = null,
    val dropped: Int? = null,
    val terminationReason: ShadowCaseEndReason? = null,
)

object CaseTraceValidator {
    fun violations(entries: List<CaseTraceEntry>): List<String> = entries.groupBy { it.caseId }.flatMap { (id, trace) ->
        buildList {
            val label = id.ifBlank { "<blank>" }
            if (id.isBlank()) add("blank caseId")
            if (trace.map { it.sessionId }.distinct().size != 1) add("$label session changed")
            val ends = trace.withIndex().filter { it.value.isEnd }
            if (ends.size != 1) add("$label CASE_END count ${ends.size}")
            ends.singleOrNull()?.let { end ->
                if (end.index != trace.lastIndex) add("$label CASE_END not last")
                if (trace.drop(end.index + 1).any { it.isScan }) add("$label SCAN after CASE_END")
                validateEnd(label, end.value, this)
                validateScans(label, trace.filter { it.isScan }, end.value.scheduled, this)
            }
        }
    }

    private fun validateEnd(label: String, end: CaseTraceEntry, violations: MutableList<String>) {
        if (end.terminationReason == null) violations += "$label missing termination reason"
        val counters = listOf(end.scheduled, end.executed, end.cancelled, end.dropped)
        if (counters.any { it == null }) {
            violations += "$label incomplete frame accounting"
            return
        }

        val scheduled = requireNotNull(end.scheduled)
        val executed = requireNotNull(end.executed)
        val cancelled = requireNotNull(end.cancelled)
        val dropped = requireNotNull(end.dropped)
        if (scheduled < 0) violations += "$label negative scheduledFrames"
        if (executed < 0) violations += "$label negative executedFrames"
        if (cancelled < 0) violations += "$label negative cancelledFrames"
        if (dropped < 0) violations += "$label negative droppedFrames"
        if (scheduled >= 0 && executed >= 0 && cancelled >= 0 && dropped >= 0 && scheduled != executed + cancelled + dropped) {
            violations += "$label frame accounting"
        }
    }

    private fun validateScans(label: String, scans: List<CaseTraceEntry>, scheduled: Int?, violations: MutableList<String>) {
        if (scheduled == null || scheduled < 0) return
        scans.forEach { scan -> if (scan.scanIndex !in 0 until scheduled) violations += "$label scan index ${scan.scanIndex}" }
        scans.groupBy { it.scanIndex }.filterValues { it.size > 1 }.keys.sorted().forEach { index -> violations += "$label duplicate scan index $index" }
    }
}
