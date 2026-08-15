package app.bypassads.core.actuation

import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.DecisionType
import app.bypassads.core.model.IntRect
import app.bypassads.core.model.SkipCandidate
import app.bypassads.core.model.SkipDecision
import app.bypassads.core.model.UiNodeSnapshot
import app.bypassads.core.model.UiSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate -> case state -> actuator integration (M2.1 task 2/4): ALLOW executes
 * exactly once and closes the case; every BLOCK path never executes; outcome
 * lifecycle seals the case; a fresh case is allowed again.
 */
class ActuationSessionTest {

    private val gate = ActuationGate()
    private val resolver = FreshTargetResolver()

    private class RecordingNodeActuator(
        var outcome: ActuationOutcome = ActuationOutcome.SUCCESS,
        var throwOnActuate: Boolean = false,
    ) : NodeActuator {
        val calls = mutableListOf<Unit>()
        override fun actuate(
            verdict: ActuationVerdict.Allow,
            resolution: FreshTargetResolution,
        ): ActuationOutcome {
            calls.add(Unit)
            if (throwOnActuate) error("actuator exploded")
            return outcome
        }
    }

    private fun candidate() = SkipCandidate(
        features = CandidateFeatures(
            nodeIndex = 0,
            label = "跳过",
            resourceId = null,
            bounds = IntRect(900, 60, 1050, 130),
            clickable = true,
            enabled = true,
            visibleToUser = true,
            nearestClickableAncestorBounds = null,
            screenWidth = 1080,
            screenHeight = 2400,
            stabilityHits = 2,
            countdownStepObserved = true,
        ),
        score = 88,
        evidence = emptyList(),
        risks = emptyList(),
    )

    private fun proposal(generation: Long = 1L) = ActionProposal.fromDecision(
        SkipDecision(DecisionType.WOULD_CLICK, candidate(), 80),
        "com.demo.app",
        generation,
        windowContextToken = 10L,
        elapsedMs = 1_000L,
    )!!

    private fun snapshot(text: String = "跳过", capturedAt: Long = 2_000L) = UiSnapshot(
        packageName = "com.demo.app",
        screenWidth = 1080,
        screenHeight = 2400,
        capturedAtElapsedMs = capturedAt,
        windowCount = 1,
        nodes = listOf(
            UiNodeSnapshot(
                index = 0, parentIndex = null, resourceId = null, text = text,
                contentDescription = null, className = "android.widget.Button",
                packageName = "com.demo.app", bounds = IntRect(900, 60, 1050, 130),
                clickable = true, enabled = true, visibleToUser = true, depth = 2,
                nearestClickableAncestorBounds = null,
            ),
        ),
    )

    private fun fresh(generation: Long = 1L, capturedAt: Long = 2_000L, text: String = "跳过") =
        resolver.resolve(
            snapshot = snapshot(text = text, capturedAt = capturedAt),
            packageName = "com.demo.app",
            caseGeneration = generation,
            windowContextToken = 10L,
            capturedAtElapsedMs = capturedAt,
            fingerprint = TargetFingerprint(
                label = "跳过",
                packageName = "com.demo.app",
                resourceId = null,
                bounds = IntRect(900, 60, 1050, 130),
                screenWidth = 1080,
                screenHeight = 2400,
            ),
        )

    @Test
    fun `allow executes exactly once and completes the case on success`() {
        val actuator = RecordingNodeActuator(outcome = ActuationOutcome.SUCCESS)
        val state = ActuationCaseState("case-a", 1L, 0L)
        val session = ActuationSession(gate, state, actuator)

        val result = session.run(proposal(), fresh(), modeAllowsAction = true, revalidatedScore = 88, revalidatedRisks = emptyList())

        assertEquals(ActuationResult.Executed(ActuationOutcome.SUCCESS), result)
        assertEquals(1, actuator.calls.size)
        assertEquals(ActuationCaseStatus.COMPLETED, state.status)
        assertEquals(1, state.attemptCount)
    }

    @Test
    fun `second proposal on the same case blocks and never executes`() {
        val actuator = RecordingNodeActuator()
        val state = ActuationCaseState("case-a", 1L, 0L)
        val session = ActuationSession(gate, state, actuator)

        session.run(proposal(), fresh(), true, 88, emptyList())
        val second = session.run(proposal(), fresh(), true, 88, emptyList())

        assertEquals(ActuationResult.Blocked(ActuationBlockReason.ATTEMPT_LIMIT_REACHED), second)
        assertEquals(1, actuator.calls.size)
    }

    @Test
    fun `blocked gate never reaches the actuator`() {
        val actuator = RecordingNodeActuator()
        val state = ActuationCaseState("case-a", 1L, 0L)
        val session = ActuationSession(gate, state, actuator)

        // CTA sibling in fresh tree -> CTA_RISK before any execution.
        val ctaSnapshot = UiSnapshot(
            packageName = "com.demo.app", screenWidth = 1080, screenHeight = 2400,
            capturedAtElapsedMs = 2_000L, windowCount = 1,
            nodes = listOf(
                UiNodeSnapshot(
                    index = 0, parentIndex = null, resourceId = null, text = "跳过",
                    contentDescription = null, className = "android.widget.Button",
                    packageName = "com.demo.app", bounds = IntRect(900, 60, 1050, 130),
                    clickable = true, enabled = true, visibleToUser = true, depth = 2,
                    nearestClickableAncestorBounds = null,
                ),
                UiNodeSnapshot(
                    index = 1, parentIndex = null, resourceId = null, text = "立即体验",
                    contentDescription = null, className = "android.widget.Button",
                    packageName = "com.demo.app", bounds = IntRect(500, 400, 900, 480),
                    clickable = true, enabled = true, visibleToUser = true, depth = 2,
                    nearestClickableAncestorBounds = null,
                ),
            ),
        )
        val ctaFresh = resolver.resolve(
            snapshot = ctaSnapshot,
            packageName = "com.demo.app",
            caseGeneration = 1L,
            windowContextToken = 10L,
            capturedAtElapsedMs = 2_000L,
            fingerprint = TargetFingerprint(
                label = "跳过", packageName = "com.demo.app", resourceId = null,
                bounds = IntRect(900, 60, 1050, 130), screenWidth = 1080, screenHeight = 2400,
            ),
        )

        val result = session.run(proposal(), ctaFresh, true, 88, emptyList())

        assertEquals(ActuationResult.Blocked(ActuationBlockReason.CTA_RISK), result)
        assertEquals(0, actuator.calls.size)
        assertEquals(ActuationCaseStatus.ACTIVE, state.status)
        assertEquals(0, state.attemptCount)
    }

    @Test
    fun `no-effect outcome seals the case as failed and is never retried`() {
        val actuator = RecordingNodeActuator(outcome = ActuationOutcome.NO_EFFECT)
        val state = ActuationCaseState("case-a", 1L, 0L)
        val session = ActuationSession(gate, state, actuator)

        val first = session.run(proposal(), fresh(), true, 88, emptyList())
        val second = session.run(proposal(), fresh(), true, 88, emptyList())

        assertEquals(ActuationResult.Executed(ActuationOutcome.NO_EFFECT), first)
        assertEquals(ActuationCaseStatus.FAILED, state.status)
        assertEquals(ActuationResult.Blocked(ActuationBlockReason.ATTEMPT_LIMIT_REACHED), second)
        assertEquals(1, actuator.calls.size)
    }

    @Test
    fun `uncertain outcome seals the case and is never retried`() {
        val actuator = RecordingNodeActuator(outcome = ActuationOutcome.UNCERTAIN)
        val state = ActuationCaseState("case-a", 1L, 0L)
        val session = ActuationSession(gate, state, actuator)

        session.run(proposal(), fresh(), true, 88, emptyList())
        val second = session.run(proposal(), fresh(), true, 88, emptyList())

        assertEquals(ActuationCaseStatus.FAILED, state.status)
        assertEquals(ActuationResult.Blocked(ActuationBlockReason.ATTEMPT_LIMIT_REACHED), second)
        assertEquals(1, actuator.calls.size)
    }

    @Test
    fun `actuator exception is classified uncertain and never retried`() {
        val actuator = RecordingNodeActuator(throwOnActuate = true)
        val state = ActuationCaseState("case-a", 1L, 0L)
        val session = ActuationSession(gate, state, actuator)

        val first = session.run(proposal(), fresh(), true, 88, emptyList())
        val second = session.run(proposal(), fresh(), true, 88, emptyList())

        assertEquals(ActuationResult.Executed(ActuationOutcome.UNCERTAIN), first)
        assertEquals(ActuationCaseStatus.FAILED, state.status)
        assertEquals(ActuationResult.Blocked(ActuationBlockReason.ATTEMPT_LIMIT_REACHED), second)
        assertEquals(1, actuator.calls.size)
    }

    @Test
    fun `fresh case is allowed independently after another case closed`() {
        val actuator = RecordingNodeActuator(outcome = ActuationOutcome.SUCCESS)
        val stateA = ActuationCaseState("case-a", 1L, 0L)
        val stateB = ActuationCaseState("case-b", 2L, 0L)
        val sessionA = ActuationSession(gate, stateA, actuator)
        val sessionB = ActuationSession(gate, stateB, actuator)

        sessionA.run(proposal(generation = 1L), fresh(generation = 1L), true, 88, emptyList())
        val resultB = sessionB.run(proposal(generation = 2L), fresh(generation = 2L), true, 88, emptyList())

        assertTrue(resultB is ActuationResult.Executed)
        assertEquals(2, actuator.calls.size)
        assertEquals(ActuationCaseStatus.COMPLETED, stateB.status)
    }

    @Test
    fun `generation mismatch between proposal and fresh blocks`() {
        val actuator = RecordingNodeActuator()
        val state = ActuationCaseState("case-a", 1L, 0L)
        val session = ActuationSession(gate, state, actuator)

        val result = session.run(
            proposal(generation = 1L),
            fresh(generation = 2L),
            modeAllowsAction = true,
            revalidatedScore = 88,
            revalidatedRisks = emptyList(),
        )

        assertEquals(ActuationResult.Blocked(ActuationBlockReason.STALE_GENERATION), result)
        assertEquals(0, actuator.calls.size)
    }

    @Test
    fun `stale fresh snapshot blocks and never executes`() {
        val actuator = RecordingNodeActuator()
        val state = ActuationCaseState("case-a", 1L, 0L)
        val session = ActuationSession(gate, state, actuator)

        val result = session.run(
            proposal(generation = 1L),
            fresh(generation = 1L, capturedAt = 1_000L), // equal to proposal time -> stale
            modeAllowsAction = true,
            revalidatedScore = 88,
            revalidatedRisks = emptyList(),
        )

        assertEquals(ActuationResult.Blocked(ActuationBlockReason.REVALIDATION_STALE), result)
        assertEquals(0, actuator.calls.size)
    }

    @Test
    fun `shadow mode blocks and never executes`() {
        val actuator = RecordingNodeActuator()
        val state = ActuationCaseState("case-a", 1L, 0L)
        val session = ActuationSession(gate, state, actuator)

        val result = session.run(proposal(), fresh(), modeAllowsAction = false, revalidatedScore = 88, revalidatedRisks = emptyList())

        assertEquals(ActuationResult.Blocked(ActuationBlockReason.MODE_BLOCKED), result)
        assertEquals(0, actuator.calls.size)
    }
}
