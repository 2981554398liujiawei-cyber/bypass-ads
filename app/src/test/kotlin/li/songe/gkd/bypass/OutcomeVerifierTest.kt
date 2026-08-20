package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Outcome decision logic (pure part of BypassOutcomeVerifier):
 * ActionResult=true is NOT success; only fresh-state verification is.
 */
class OutcomeVerifierTest {

    @Test
    fun action_accepted_but_ad_remains_is_not_success() {
        assertEquals(
            BypassOutcome.ACTION_NO_EFFECT,
            BypassOutcomeVerifier.decide(
                stillMatches = true,
                context = BypassAdContextLevel.STRONG,
                topChanged = false,
                topChangedToExternal = false,
            ),
        )
    }

    @Test
    fun candidate_gone_and_context_gone_is_success() {
        assertEquals(
            BypassOutcome.SUCCESS_CONFIRMED,
            BypassOutcomeVerifier.decide(
                stillMatches = false,
                context = BypassAdContextLevel.NONE,
                topChanged = false,
                topChangedToExternal = false,
            ),
        )
    }

    @Test
    fun candidate_gone_but_strong_ad_context_remains_is_no_effect() {
        assertEquals(
            BypassOutcome.ACTION_NO_EFFECT,
            BypassOutcomeVerifier.decide(
                stillMatches = false,
                context = BypassAdContextLevel.STRONG,
                topChanged = false,
                topChangedToExternal = false,
            ),
        )
    }

    @Test
    fun external_landing_is_misclick_and_stops_immediately() {
        assertEquals(
            BypassOutcome.MISCLICK_SUSPECTED,
            BypassOutcomeVerifier.decide(
                stillMatches = false,
                context = BypassAdContextLevel.NONE,
                topChanged = true,
                topChangedToExternal = true,
            ),
        )
    }

    @Test
    fun unknown_top_change_is_unresolved() {
        assertEquals(
            BypassOutcome.UNRESOLVED,
            BypassOutcomeVerifier.decide(
                stillMatches = false,
                context = BypassAdContextLevel.NONE,
                topChanged = true,
                topChangedToExternal = false,
            ),
        )
    }

    @Test
    fun external_landing_packages_are_recognized() {
        assertTrue(BypassOutcomeVerifier.isExternalLanding("com.android.chrome"))
        assertTrue(BypassOutcomeVerifier.isExternalLanding("com.xiaomi.market"))
        assertTrue(BypassOutcomeVerifier.isExternalLanding("com.tencent.mtt"))
        assertTrue(!BypassOutcomeVerifier.isExternalLanding("com.tencent.mm"))
        assertTrue(!BypassOutcomeVerifier.isExternalLanding("com.example.app"))
    }
}
