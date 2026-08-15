package app.bypassads.core.actuation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Outcome contract (ADR-0002 §5): SUCCESS / NO_EFFECT / UNCERTAIN and the
 * one-attempt-per-case rule. Defined in M2.0; executed by a future actuator.
 */
class ActuationOutcomeTest {

    @Test
    fun `target disappeared is success`() {
        assertEquals(
            ActuationOutcome.SUCCESS,
            ActuationOutcomeClassifier.classify(
                targetDisappeared = true,
                splashWindowGone = false,
                nonAdStateVisible = false,
                targetStillPresent = false,
                windowStillPresent = true,
            ),
        )
    }

    @Test
    fun `splash window gone is success`() {
        assertEquals(
            ActuationOutcome.SUCCESS,
            ActuationOutcomeClassifier.classify(
                targetDisappeared = false,
                splashWindowGone = true,
                nonAdStateVisible = false,
                targetStillPresent = true,
                windowStillPresent = false,
            ),
        )
    }

    @Test
    fun `non-ad state visible is success`() {
        assertEquals(
            ActuationOutcome.SUCCESS,
            ActuationOutcomeClassifier.classify(
                targetDisappeared = false,
                splashWindowGone = false,
                nonAdStateVisible = true,
                targetStillPresent = true,
                windowStillPresent = true,
            ),
        )
    }

    @Test
    fun `target and window unchanged is no-effect`() {
        assertEquals(
            ActuationOutcome.NO_EFFECT,
            ActuationOutcomeClassifier.classify(
                targetDisappeared = false,
                splashWindowGone = false,
                nonAdStateVisible = false,
                targetStillPresent = true,
                windowStillPresent = true,
            ),
        )
    }

    @Test
    fun `insufficient evidence is uncertain`() {
        assertEquals(
            ActuationOutcome.UNCERTAIN,
            ActuationOutcomeClassifier.classify(
                targetDisappeared = false,
                splashWindowGone = false,
                nonAdStateVisible = false,
                targetStillPresent = false,
                windowStillPresent = true,
            ),
        )
        assertEquals(
            ActuationOutcome.UNCERTAIN,
            ActuationOutcomeClassifier.classify(
                targetDisappeared = false,
                splashWindowGone = false,
                nonAdStateVisible = false,
                targetStillPresent = true,
                windowStillPresent = false,
            ),
        )
    }

    @Test
    fun `at most one actuation attempt per case`() {
        assertEquals(1, MAX_ACTUATION_ATTEMPTS_PER_CASE)
    }

    @Test
    fun `proposal is not produced for non-would-click decisions`() {
        val decision = app.bypassads.core.model.SkipDecision(
            app.bypassads.core.model.DecisionType.TOO_RISKY,
            null,
            80,
        )
        assertEquals(
            null,
            ActionProposal.fromDecision(decision, "com.demo.app", 1L, 10L, 1_000L),
        )
    }
}
