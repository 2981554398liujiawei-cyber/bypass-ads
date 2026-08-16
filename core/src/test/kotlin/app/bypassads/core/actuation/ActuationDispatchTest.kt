package app.bypassads.core.actuation

import kotlin.test.Test
import kotlin.test.assertEquals

class ActuationDispatchTest {

    @Test
    fun zeroMatches_isImmediateNoEffect() {
        assertEquals(ActuationOutcome.NO_EFFECT, dispatchOutcome(0))
    }

    @Test
    fun singleMatch_isUncertainUntilVerified() {
        // One dispatched click: the HyperOS performAction boolean is unreliable,
        // so the immediate value must never be NO_EFFECT (and never SUCCESS).
        assertEquals(ActuationOutcome.UNCERTAIN, dispatchOutcome(1))
    }

    @Test
    fun multipleMatches_isUncertainAndNoClick() {
        assertEquals(ActuationOutcome.UNCERTAIN, dispatchOutcome(2))
        assertEquals(ActuationOutcome.UNCERTAIN, dispatchOutcome(5))
    }
}
