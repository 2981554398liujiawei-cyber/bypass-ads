package app.bypassads.evidence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

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
    fun `aggregates count-only timing and lifecycle evidence after the cutoff`() {
        val report = JsonlTraceValidatorRunner.validate(
            sequenceOf(
                """{"time":99,"session":1,"caseId":"old","scan":0,"trigger":"SCAN","scanWorkMs":9999}""",
                """{"time":100,"session":2,"caseId":"case-a","scan":0,"trigger":"SCAN","scanWorkMs":10,"windowAcquireMs":1,"snapshotMs":2,"detectionMs":3,"decisionMs":4,"rootAcquireMaxMs":5,"childQueryMaxMs":6,"captureBudgetHit":true,"workerBusyDrops":1,"workerMaxQueueDepth":1}""",
                """{"time":101,"session":2,"caseId":"case-a","scan":1,"trigger":"SCAN","scanWorkMs":20,"workerBusyDrops":2,"workerMaxQueueDepth":1}""",
                """{"time":102,"session":2,"caseId":"case-a","scan":2,"trigger":"SCAN","scanWorkMs":1000}""",
                """{"time":103,"session":2,"caseId":"case-a","scan":-1,"trigger":"CASE_END","scheduledFrames":3,"executedFrames":3,"cancelledFrames":0,"droppedFrames":0,"terminationReason":"COMPLETED"}""",
            ),
            fromEpochMs = 100,
        )

        val summary = report.evidenceSummary
        assertEquals(4, summary.postCutoffRecords)
        assertEquals(1, summary.distinctCases)
        assertEquals(3, summary.scanRecords)
        assertEquals(1, summary.terminalRecords)
        assertEquals(3L, summary.durationMs)
        assertEquals(mapOf("COMPLETED" to 1), summary.terminationReasons)
        assertEquals(MetricSummary(3, 3, 0, 0, 20, 1000, 1000, 1), summary.timings.getValue("scanWorkMs"))
        assertEquals(MetricSummary(3, 1, 2, 0, 1, 1, 1, 0), summary.timings.getValue("windowAcquireMs"))
        assertEquals(BooleanMetricSummary(3, 1, 0, 2, 0), summary.captureBudget)
        assertEquals(CounterSummary(3, 2, 1, 0, 1, 2, 2, 0, 1), summary.workerBusyDrops)
        assertEquals(MetricSummary(3, 2, 1, 0, 1, 1, 1, 0), summary.workerMaxQueueDepth)
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
    fun `accepts only service connection records without a case identity`() {
        val report = JsonlTraceValidatorRunner.validate(
            sequenceOf(
                """{"time":100,"session":7,"caseId":null,"scan":-1,"trigger":"SERVICE_CONNECTED"}""",
                """{"time":101,"session":7,"caseId":null,"scan":0,"trigger":"SCAN"}""",
                """{"time":102,"session":7,"scan":-1,"trigger":"CASE_END"}""",
                """{"time":103,"session":7,"caseId":null,"scan":-1,"trigger":"UNKNOWN"}""",
            ),
        )

        assertFalse(report.passed)
        assertEquals(1, report.nonCaseRecords)
        assertEquals(3, report.unadaptableCaseRecords)
    }

    @Test
    fun `rejects command line options without a value`() {
        assertFailsWith<IllegalStateException> {
            parseOptions(arrayOf("--input", "trace.jsonl", "--from-epoch-ms"))
        }
    }

    @Test
    fun `reports missing and invalid scan metrics instead of treating them as zero`() {
        val report = JsonlTraceValidatorRunner.validate(
            sequenceOf(
                """{"time":100,"session":7,"caseId":"case-a","scan":0,"trigger":"SCAN","scanWorkMs":"0","windowAcquireMs":-1,"captureBudgetHit":"false","workerBusyDrops":0.5,"workerMaxQueueDepth":0}""",
            ),
        )

        val summary = report.evidenceSummary
        assertEquals(MetricSummary(1, 0, 0, 1, null, null, null, 0), summary.timings.getValue("scanWorkMs"))
        assertEquals(MetricSummary(1, 0, 0, 1, null, null, null, 0), summary.timings.getValue("windowAcquireMs"))
        assertEquals(BooleanMetricSummary(1, 0, 0, 0, 1), summary.captureBudget)
        assertEquals(CounterSummary(1, 0, 0, 1, null, null, null, 0, 0), summary.workerBusyDrops)
        assertFalse(summary.timings.getValue("scanWorkMs").complete)
    }

    @Test
    fun `validates a local M14 sidecar without publishing mini program names`() {
        val sidecar = M14SessionSidecar.parse(
            JSONObject(
                """{
                    "collectionStartEpochMs":1000,
                    "offCycles":[
                      {"offAtEpochMs":2000,"shadowRestoredAtEpochMs":32000},
                      {"offAtEpochMs":33000,"shadowRestoredAtEpochMs":63000},
                      {"offAtEpochMs":64000,"shadowRestoredAtEpochMs":94000},
                      {"offAtEpochMs":95000,"shadowRestoredAtEpochMs":125000},
                      {"offAtEpochMs":126000,"shadowRestoredAtEpochMs":156000}
                    ],
                    "finalIoHealth":{"pendingIoJobs":0,"maxPendingIoJobs":3,"recordsWritten":340,"writeFailures":0},
                    "coverage":{"ordinaryAppCount":10,"wechatMiniProgramNames":["A","B","C"],"alipayMiniProgramNames":["D","E","F"]},
                    "stability":{"crashCount":0,"anrCount":0}
                }""".trimIndent(),
            ),
        )
        val trace = listOf(
            TraceObservation(1000, "SCAN", "case-a"),
            TraceObservation(32001, "SCAN", "case-a"),
            TraceObservation(63001, "SCAN", "case-a"),
            TraceObservation(94001, "SCAN", "case-a"),
            TraceObservation(125001, "SCAN", "case-a"),
            TraceObservation(156001, "SCAN", "case-a"),
        )

        val result = M14SessionSidecarValidator.validate(sidecar, trace, 1000)

        assertTrue(result.collectionStartMatchesTraceCutoff)
        assertEquals(5, result.validOffCycleCount)
        assertEquals(emptyList(), result.lifecycleViolationCodes)
        assertTrue(result.finalIoHealth!!.passed)
        assertTrue(result.coverage!!.passed)
        assertTrue(result.stability!!.passed)
        val published = result.toJson().toString()
        assertFalse(published.contains("\"A\""))
        assertFalse(published.contains("\"D\""))
    }

    @Test
    fun `reports lifecycle violations and failed final health from the local sidecar`() {
        val sidecar = M14SessionSidecar.parse(
            JSONObject(
                """{
                    "collectionStartEpochMs":1000,
                    "offCycles":[{"offAtEpochMs":2000,"shadowRestoredAtEpochMs":3000}],
                    "finalIoHealth":{"pendingIoJobs":1,"maxPendingIoJobs":1,"recordsWritten":1,"writeFailures":1},
                    "coverage":{"ordinaryAppCount":9,"wechatMiniProgramNames":["same","same"],"alipayMiniProgramNames":["only"]},
                    "stability":{"crashCount":1,"anrCount":0}
                }""".trimIndent(),
            ),
        )

        val result = M14SessionSidecarValidator.validate(sidecar, listOf(TraceObservation(2500, "SCAN", "new-case")), 1000)

        assertTrue(result.collectionStartMatchesTraceCutoff)
        assertEquals(0, result.validOffCycleCount)
        assertTrue(result.lifecycleViolationCodes.containsAll(listOf("OFF_CYCLE_1_TOO_SHORT", "OFF_CYCLE_1_SCAN_WHILE_OFF", "OFF_CYCLE_1_NEW_CASE_WHILE_OFF", "OFF_CYCLE_1_NO_SCAN_AFTER_RESTORE", "TOO_FEW_OFF_CYCLES")))
        assertFalse(result.finalIoHealth!!.passed)
        assertFalse(result.coverage!!.passed)
        assertFalse(result.stability!!.passed)
        assertFalse(result.passed)
    }
}
