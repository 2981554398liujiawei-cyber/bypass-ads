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
)

object CaseTraceValidator {
    fun violations(entries: List<CaseTraceEntry>): List<String> = entries.groupBy { it.caseId }.flatMap { (id, trace) ->
        buildList {
            if (trace.map { it.sessionId }.distinct().size != 1) add("$id session changed")
            val ends = trace.withIndex().filter { it.value.isEnd }
            if (ends.size != 1) add("$id CASE_END count ${ends.size}")
            ends.firstOrNull()?.let { end -> if (trace.drop(end.index + 1).any { it.isScan }) add("$id SCAN after CASE_END") }
            trace.filter { it.isScan }.forEach { scan ->
                val frames = trace.firstOrNull { it.isEnd }?.scheduled
                if (frames != null && scan.scanIndex !in 0 until frames) add("$id scan index ${scan.scanIndex}")
            }
            trace.firstOrNull { it.isEnd }?.let { end ->
                val accounting = listOfNotNull(end.executed, end.cancelled, end.dropped)
                if (end.scheduled != null && accounting.size != 3) add("$id incomplete frame accounting")
                if (end.scheduled != null && accounting.size == 3 && end.scheduled != accounting.sum()) add("$id frame accounting")
            }
        }
    }
}
