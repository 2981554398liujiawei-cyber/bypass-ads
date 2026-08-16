package app.bypassads.core.actuation

import kotlin.test.Test
import kotlin.test.assertEquals

class ActuationOutcomeClassifierTest {

    @Test
    fun targetDisappeared_isSuccess() {
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
    fun splashWindowGone_isSuccess() {
        assertEquals(
            ActuationOutcome.SUCCESS,
            ActuationOutcomeClassifier.classify(
                targetDisappeared = false,
                splashWindowGone = true,
                nonAdStateVisible = false,
                targetStillPresent = false,
                windowStillPresent = false,
            ),
        )
    }

    @Test
    fun targetAndWindowStillPresent_isNoEffect() {
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
    fun insufficientEvidence_isUncertain() {
        // No positive evidence for success and no proof of no-effect (e.g. no
        // window information at verification time) must never claim SUCCESS.
        assertEquals(
            ActuationOutcome.UNCERTAIN,
            ActuationOutcomeClassifier.classify(
                targetDisappeared = false,
                splashWindowGone = false,
                nonAdStateVisible = false,
                targetStillPresent = false,
                windowStillPresent = false,
            ),
        )
    }
}
