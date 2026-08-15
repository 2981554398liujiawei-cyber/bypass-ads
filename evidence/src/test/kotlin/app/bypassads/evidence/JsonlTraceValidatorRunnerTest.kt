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
    fun `allows only a one-record non-scan open case at the local trace tail`() {
        val report = JsonlTraceValidatorRunner.validate(
            sequenceOf(
                """{"time":100,"session":1,"caseId":"complete","scan":-1,"trigger":"WINDOW_CHANGED"}""",
                """{"time":101,"session":1,"caseId":"complete","scan":-1,"trigger":"CASE_END","scheduledFrames":0,"executedFrames":0,"cancelledFrames":0,"droppedFrames":0,"terminationReason":"COMPLETED"}""",
                """{"time":102,"session":1,"caseId":"tail","scan":-1,"trigger":"WINDOWS_CHANGED"}""",
            ),
        )

        assertTrue(report.traceIntegrityPassed)
        assertEquals(1, report.openTailCaseCount)

        val nonTail = JsonlTraceValidatorRunner.validate(
            sequenceOf(
                """{"time":100,"session":1,"caseId":"open","scan":-1,"trigger":"WINDOW_CHANGED"}""",
                """{"time":101,"session":1,"caseId":"complete","scan":-1,"trigger":"WINDOW_CHANGED"}""",
                """{"time":102,"session":1,"caseId":"complete","scan":-1,"trigger":"CASE_END","scheduledFrames":0,"executedFrames":0,"cancelledFrames":0,"droppedFrames":0,"terminationReason":"COMPLETED"}""",
            ),
        )
        assertFalse(nonTail.traceIntegrityPassed)
        assertEquals(0, nonTail.openTailCaseCount)

        val scannedTail = JsonlTraceValidatorRunner.validate(
            sequenceOf(
                """{"time":100,"session":1,"caseId":"tail","scan":-1,"trigger":"WINDOW_CHANGED"}""",
                """{"time":101,"session":1,"caseId":"tail","scan":0,"trigger":"SCAN"}""",
            ),
        )
        assertFalse(scannedTail.traceIntegrityPassed)
        assertEquals(0, scannedTail.openTailCaseCount)

        val multiRecordTail = JsonlTraceValidatorRunner.validate(
            sequenceOf(
                """{"time":100,"session":1,"caseId":"tail","scan":-1,"trigger":"WINDOW_CHANGED"}""",
                """{"time":101,"session":1,"caseId":"tail","scan":-1,"trigger":"CONTENT_CHANGED"}""",
            ),
        )
        assertFalse(multiRecordTail.traceIntegrityPassed)
        assertEquals(0, multiRecordTail.openTailCaseCount)
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
    fun `accepts an experimental action attempt as an ordinary mid-case record`() {
        val report = JsonlTraceValidatorRunner.validate(
            sequenceOf(
                """{"time":100,"session":7,"caseId":"case-a","scan":-1,"trigger":"PACKAGE_CHANGED"}""",
                """{"time":101,"session":7,"caseId":"case-a","scan":0,"trigger":"SCAN"}""",
                """{"time":102,"session":7,"caseId":"case-a","scan":-1,"trigger":"ACTION_ATTEMPT","actuationVerdict":"ALLOW","actuationOutcome":"UNCERTAIN"}""",
                """{"time":103,"session":7,"caseId":"case-a","scan":1,"trigger":"SCAN"}""",
                """{"time":104,"session":7,"caseId":"case-a","scan":-1,"trigger":"CASE_END","scheduledFrames":2,"executedFrames":2,"cancelledFrames":0,"droppedFrames":0,"terminationReason":"COMPLETED"}""",
            ),
        )

        assertTrue(report.traceIntegrityPassed)
        assertEquals(0, report.unadaptableCaseRecords)
        assertEquals(emptyList(), report.validatorViolations)
        assertEquals(5, report.caseRecords)
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
                    "collectionEndEpochMs":200000,
                    "controlledRun":{"startEpochMs":1000,"endEpochMs":200000},
                    "continuousCollectionConfirmed":true,
                    "serviceBoundAtEnd":true,
                    "offCycles":[
                      {"offAtEpochMs":2000,"shadowRestoredAtEpochMs":32000},
                      {"offAtEpochMs":33000,"shadowRestoredAtEpochMs":63000},
                      {"offAtEpochMs":64000,"shadowRestoredAtEpochMs":94000},
                      {"offAtEpochMs":95000,"shadowRestoredAtEpochMs":125000},
                      {"offAtEpochMs":126000,"shadowRestoredAtEpochMs":156000}
                    ],
                    "finalIoHealth":{"pendingIoJobs":0,"maxPendingIoJobs":3,"recordsWritten":340,"writeFailures":0},
                    "coverage":{"ordinaryAppCount":10,"wechatMiniProgramNames":["A","B","C"],"alipayMiniProgramNames":["D","E","F"]},
                    "stability":{"crashCount":0,"anrCount":0},
                    "slowReview":{"unexplainedSlowAccessibilityCount":0,"repeatableMultiSecondBlockingCount":0}
                }""".trimIndent(),
            ),
        )
        val trace = listOf(
            TraceObservation(1000, "SCAN", "case-a"),
            TraceObservation(2000, "CASE_END", "off-a", "MODE_OFF"),
            TraceObservation(2001, "WINDOW_CHANGED", "recovery-window"),
            TraceObservation(32001, "SCAN", "case-a"),
            TraceObservation(33000, "CASE_END", "off-b", "MODE_OFF"),
            TraceObservation(33001, "CONTENT_CHANGED", "recovery-content"),
            TraceObservation(63001, "SCAN", "case-a"),
            TraceObservation(64000, "CASE_END", "off-c", "MODE_OFF"),
            TraceObservation(64001, "PACKAGE_CHANGED", "recovery-package"),
            TraceObservation(94001, "SCAN", "case-a"),
            TraceObservation(95000, "CASE_END", "off-d", "MODE_OFF"),
            TraceObservation(125001, "SCAN", "case-a"),
            TraceObservation(126000, "CASE_END", "off-e", "MODE_OFF"),
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
        assertTrue(result.lifecycleViolationCodes.containsAll(listOf("OFF_CYCLE_1_MISSING_MODE_OFF_ANCHOR", "OFF_CYCLE_1_TOO_SHORT", "OFF_CYCLE_1_SCAN_WHILE_OFF", "OFF_CYCLE_1_NO_SCAN_AFTER_RESTORE", "TOO_FEW_OFF_CYCLES")))
        assertFalse(result.finalIoHealth!!.passed)
        assertFalse(result.coverage!!.passed)
        assertFalse(result.stability!!.passed)
        assertFalse(result.passed)
    }

    @Test
    fun `release evidence requires every M14 session gate`() {
        val report = JsonlTraceValidatorRunner.validate(completeJsonl().asSequence(), fromEpochMs = 1000, sidecar = completeSidecar())

        assertTrue(report.traceIntegrityPassed)
        assertTrue(report.scanMetricsComplete)
        assertTrue(report.sessionValidation!!.passed)
        assertTrue(report.releaseEvidenceReady)

        val spreadAcrossSoak = List(300) { index -> TraceObservation(1000L + index * 12_000L, "SCAN", "case-a") } + restoredCycleScans()
        assertFalse(M14SessionSidecarValidator.validate(completeSidecar(), spreadAcrossSoak, 1000).controlledRun!!.passed)
        assertFalse(M14SessionSidecarValidator.validate(completeSidecar().copy(continuousCollectionConfirmed = false), completeTrace(), 1000).soak!!.passed)
        assertFalse(M14SessionSidecarValidator.validate(completeSidecar().copy(slowReview = M14SessionSidecar.SlowReview(1, 0)), completeTrace(), 1000).slowReview!!.passed)

        val overlappingCycles = completeSidecar().offCycles.toMutableList().also { cycles -> cycles[1] = cycles[1].copy(offAtEpochMs = cycles[0].shadowRestoredAtEpochMs) }
        val lifecycle = M14SessionSidecarValidator.validate(completeSidecar().copy(offCycles = overlappingCycles), completeTrace(), 1000)
        assertTrue(lifecycle.lifecycleViolationCodes.contains("OFF_CYCLE_2_NOT_STRICTLY_AFTER_PREVIOUS"))
        assertFalse(lifecycle.passed)

        val beforeCollection = completeSidecar().offCycles.toMutableList().also { cycles -> cycles[0] = cycles[0].copy(offAtEpochMs = 999) }
        assertTrue(M14SessionSidecarValidator.validate(completeSidecar().copy(offCycles = beforeCollection), completeTrace(), 1000).lifecycleViolationCodes.contains("OFF_CYCLE_1_OUTSIDE_COLLECTION"))

        val missingAnchor = completeSidecar().offCycles.toMutableList().also { cycles -> cycles[0] = M14SessionSidecar.OffCycle(950001, 980001) }
        assertTrue(M14SessionSidecarValidator.validate(completeSidecar().copy(offCycles = missingAnchor), completeTrace(), 1000).lifecycleViolationCodes.contains("OFF_CYCLE_1_MISSING_MODE_OFF_ANCHOR"))

        val reusedAnchor = completeSidecar().offCycles.toMutableList().also { cycles -> cycles[1] = M14SessionSidecar.OffCycle(950000, 980000) }
        assertTrue(M14SessionSidecarValidator.validate(completeSidecar().copy(offCycles = reusedAnchor), completeTrace(), 1000).lifecycleViolationCodes.contains("OFF_CYCLE_2_DUPLICATE_MODE_OFF_ANCHOR"))

        val onlyFourAnchors = completeTrace().filterNot { it.epochMs == 1110000L && it.terminationReason == "MODE_OFF" }
        assertTrue(M14SessionSidecarValidator.validate(completeSidecar(), onlyFourAnchors, 1000).lifecycleViolationCodes.contains("OFF_CYCLE_5_MISSING_MODE_OFF_ANCHOR"))
    }

    private fun completeSidecar(): M14SessionSidecar = M14SessionSidecar.parse(
        JSONObject(
            """{
                "collectionStartEpochMs":1000,
                "collectionEndEpochMs":3601000,
                "controlledRun":{"startEpochMs":1000,"endEpochMs":901000},
                "continuousCollectionConfirmed":true,
                "serviceBoundAtEnd":true,
                "offCycles":[
                  {"offAtEpochMs":950000,"shadowRestoredAtEpochMs":980000},
                  {"offAtEpochMs":990000,"shadowRestoredAtEpochMs":1020000},
                  {"offAtEpochMs":1030000,"shadowRestoredAtEpochMs":1060000},
                  {"offAtEpochMs":1070000,"shadowRestoredAtEpochMs":1100000},
                  {"offAtEpochMs":1110000,"shadowRestoredAtEpochMs":1140000}
                ],
                "finalIoHealth":{"pendingIoJobs":0,"maxPendingIoJobs":2,"recordsWritten":306,"writeFailures":0},
                "coverage":{"ordinaryAppCount":10,"wechatMiniProgramNames":["one","two","three"],"alipayMiniProgramNames":["four","five","six"]},
                "stability":{"crashCount":0,"anrCount":0},
                "slowReview":{"unexplainedSlowAccessibilityCount":0,"repeatableMultiSecondBlockingCount":0}
            }""".trimIndent(),
        ),
    )

    private fun completeTrace(): List<TraceObservation> = List(300) { index -> TraceObservation(1000L + index * 3_000L, "SCAN", "case-a") } + modeOffAnchors() + restoredCycleScans()

    private fun modeOffAnchors(): List<TraceObservation> = listOf(
        TraceObservation(950000, "CASE_END", "off-a", "MODE_OFF"),
        TraceObservation(990000, "CASE_END", "off-b", "MODE_OFF"),
        TraceObservation(1030000, "CASE_END", "off-c", "MODE_OFF"),
        TraceObservation(1070000, "CASE_END", "off-d", "MODE_OFF"),
        TraceObservation(1110000, "CASE_END", "off-e", "MODE_OFF"),
    )

    private fun restoredCycleScans(): List<TraceObservation> = listOf(
        TraceObservation(980001, "SCAN", "case-a"),
        TraceObservation(1020001, "SCAN", "case-a"),
        TraceObservation(1060001, "SCAN", "case-a"),
        TraceObservation(1100001, "SCAN", "case-a"),
        TraceObservation(1140001, "SCAN", "case-a"),
        TraceObservation(3601000, "SCAN", "case-a"),
    )

    private fun completeJsonl(): List<String> = buildList {
        add("""{"time":1000,"session":1,"caseId":"case-a","scan":-1,"trigger":"PACKAGE_CHANGED"}""")
        repeat(300) { index -> add(scanLine(1000L + index * 3_000L, index)) }
        val modeOffTimes = listOf(950000L, 990000L, 1030000L, 1070000L, 1110000L)
        modeOffTimes.forEachIndexed { index, time ->
            add("""{"time":${time - 1},"session":1,"caseId":"off-$index","scan":-1,"trigger":"WINDOW_CHANGED"}""")
            add("""{"time":$time,"session":1,"caseId":"off-$index","scan":-1,"trigger":"CASE_END","scheduledFrames":0,"executedFrames":0,"cancelledFrames":0,"droppedFrames":0,"terminationReason":"MODE_OFF"}""")
        }
        val restoreTimes = listOf(980001L, 1020001L, 1060001L, 1100001L, 1140001L, 3601000L)
        restoreTimes.forEachIndexed { offset, time -> add(scanLine(time, 300 + offset)) }
        add("""{"time":3601001,"session":1,"caseId":"case-a","scan":-1,"trigger":"CASE_END","scheduledFrames":306,"executedFrames":306,"cancelledFrames":0,"droppedFrames":0,"terminationReason":"COMPLETED"}""")
    }

    private fun scanLine(time: Long, scan: Int): String = """{"time":$time,"session":1,"caseId":"case-a","scan":$scan,"trigger":"SCAN","scanWorkMs":10,"windowAcquireMs":1,"snapshotMs":2,"detectionMs":3,"decisionMs":4,"rootAcquireMaxMs":5,"childQueryMaxMs":6,"captureBudgetHit":false,"workerBusyDrops":0,"workerMaxQueueDepth":1}"""
}
