package app.bypassads.evidence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonlTraceValidatorRunnerTest {
    @Test
    fun `maps a balanced JSONL case through the existing validator`() {
        val report = JsonlTraceValidatorRunner.validate(
            sequenceOf(
                """{"time":100,"session":7,"caseId":"case-a","scan":-1,"trigger":"PACKAGE_CHANGED"}""",
                """{"time":101,"session":7,"caseId":"case-a","scan":0,"trigger":"SCAN"}""",
                """{"time":102,"session":7,"caseId":"case-a","scan":-1,"trigger":"CASE_END","scheduledFrames":2,"executedFrames":1,"cancelledFrames":1,"droppedFrames":0,"terminationReason":"COMPLETED"}""",
                """{"time":103,"session":8,"caseId":null,"scan":-1,"trigger":"SERVICE_CONNECTED"}""",
            ),
        )

        assertTrue(report.passed)
        assertEquals(4, report.inputLines)
        assertEquals(1, report.nonCaseRecords)
        assertEquals(3, report.caseRecords)
        assertEquals(emptyList(), report.validatorViolations)
    }

    @Test
    fun `reports malformed unadaptable and validator-invalid trace data`() {
        val report = JsonlTraceValidatorRunner.validate(
            sequenceOf(
                "not json",
                """{"time":101,"session":7,"caseId":"case-a","scan":0,"trigger":"SCAN"}""",
                """{"time":102,"session":7,"caseId":"case-a","scan":0,"trigger":"SCAN"}""",
                """{"time":103,"session":7,"caseId":"case-a","scan":-1,"trigger":"CASE_END","scheduledFrames":2,"executedFrames":1,"cancelledFrames":1,"droppedFrames":0,"terminationReason":"COMPLETED"}""",
                """{"time":104,"session":"wrong","caseId":"case-b","scan":-1,"trigger":"CASE_END"}""",
            ),
        )

        assertFalse(report.passed)
        assertEquals(1, report.malformedJsonLines)
        assertEquals(1, report.unadaptableCaseRecords)
        assertEquals(listOf("case-a duplicate scan index 0"), report.validatorViolations)
    }

    @Test
    fun `filters records before a declared collection start`() {
        val report = JsonlTraceValidatorRunner.validate(
            sequenceOf(
                """{"time":99,"session":1,"caseId":"old","scan":0,"trigger":"SCAN"}""",
                """{"time":100,"session":2,"caseId":"case-a","scan":0,"trigger":"SCAN"}""",
                """{"time":101,"session":2,"caseId":"case-a","scan":-1,"trigger":"CASE_END","scheduledFrames":1,"executedFrames":1,"cancelledFrames":0,"droppedFrames":0,"terminationReason":"COMPLETED"}""",
            ),
            fromEpochMs = 100,
        )

        assertTrue(report.passed)
        assertEquals(1, report.ignoredBeforeStart)
        assertEquals(2, report.caseRecords)
    }

    @Test
    fun `rejects a case record with an unknown trigger`() {
        val report = JsonlTraceValidatorRunner.validate(
            sequenceOf("""{"time":100,"session":7,"caseId":"case-a","scan":0,"trigger":"SCAN_V2"}"""),
        )

        assertFalse(report.passed)
        assertEquals(1, report.unadaptableCaseRecords)
    }

    @Test
    fun `rejects command line options without a value`() {
        assertFailsWith<IllegalStateException> {
            parseOptions(arrayOf("--input", "trace.jsonl", "--from-epoch-ms"))
        }
    }
}
