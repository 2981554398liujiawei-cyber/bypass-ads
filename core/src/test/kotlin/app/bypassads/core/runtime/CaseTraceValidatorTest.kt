package app.bypassads.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class CaseTraceValidatorTest {
    @Test fun `accepts a balanced complete case`() {
        val trace = listOf(
            scan("case-a", 10, 0),
            scan("case-a", 10, 1),
            end("case-a", 10, scheduled = 3, executed = 2, cancelled = 1, dropped = 0),
        )

        assertEquals(emptyList(), CaseTraceValidator.violations(trace))
    }

    @Test fun `reports session change end boundary and scan index violations`() {
        val trace = listOf(
            scan("case-a", 10, 3),
            end("case-a", 10, scheduled = 3, executed = 1, cancelled = 2, dropped = 0),
            scan("case-a", 11, 0),
        )

        assertEquals(
            listOf("case-a session changed", "case-a SCAN after CASE_END", "case-a scan index 3"),
            CaseTraceValidator.violations(trace),
        )
    }

    @Test fun `reports missing and unbalanced accounting without throwing`() {
        val missingCounters = listOf(end("case-a", 10, scheduled = 3, executed = 2, cancelled = null, dropped = 1))
        val unbalancedCounters = listOf(end("case-b", 20, scheduled = 3, executed = 1, cancelled = 1, dropped = 0))

        assertEquals(listOf("case-a incomplete frame accounting"), CaseTraceValidator.violations(missingCounters))
        assertEquals(listOf("case-b frame accounting"), CaseTraceValidator.violations(unbalancedCounters))
    }

    private fun scan(caseId: String, sessionId: Long, scanIndex: Int) = CaseTraceEntry(caseId, sessionId, scanIndex, isScan = true, isEnd = false)

    private fun end(caseId: String, sessionId: Long, scheduled: Int, executed: Int?, cancelled: Int?, dropped: Int?) =
        CaseTraceEntry(caseId, sessionId, scanIndex = -1, isScan = false, isEnd = true, scheduled, executed, cancelled, dropped)
}
