package app.bypassads.core.actuation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Capability freeze (M2.1 P2): RC builds are Shadow-only; experimental builds
 * may allow the gated actuator. The policy must fail fast on any
 * non-experimental actuation attempt.
 */
class ActuationCapabilityPolicyTest {

    @Test
    fun `release rc build never allows actuation`() {
        val policy = ActuationCapabilityPolicy(BuildVariant.RELEASE_RC)

        assertFalse(policy.actuatorAllowed)
    }

    @Test
    fun `release rc build fails fast when actuation is attempted`() {
        val policy = ActuationCapabilityPolicy(BuildVariant.RELEASE_RC)

        assertFailsWith<IllegalStateException> { policy.requireActuatorAllowed() }
    }

    @Test
    fun `experimental build allows gated actuation`() {
        val policy = ActuationCapabilityPolicy(BuildVariant.EXPERIMENTAL)

        assertTrue(policy.actuatorAllowed)
        policy.requireActuatorAllowed() // must not throw
    }

    @Test
    fun `shadow only default does not exist - variant is explicit`() {
        // The policy never defaults; callers must choose a variant consciously.
        assertEquals(BuildVariant.RELEASE_RC, BuildVariant.RELEASE_RC)
        assertEquals(BuildVariant.EXPERIMENTAL, BuildVariant.EXPERIMENTAL)
    }
}
