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
    fun action_accepted_but_ad_candidate_remains_is_not_success() {
        assertEquals(
            BypassOutcome.ACTION_NO_EFFECT,
            BypassOutcomeVerifier.decide(
                freshAdCandidateExists = true,
                shellContextStrong = false,
                topChanged = false,
                topChangedToExternal = false,
            ),
        )
    }

    @Test
    fun mini_program_shell_still_foreground_is_not_success() {
        assertEquals(
            BypassOutcome.ACTION_NO_EFFECT,
            BypassOutcomeVerifier.decide(
                freshAdCandidateExists = false,
                shellContextStrong = true,
                topChanged = false,
                topChangedToExternal = false,
            ),
        )
    }

    @Test
    fun no_candidate_and_no_shell_is_success() {
        assertEquals(
            BypassOutcome.SUCCESS_CONFIRMED,
            BypassOutcomeVerifier.decide(
                freshAdCandidateExists = false,
                shellContextStrong = false,
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
                freshAdCandidateExists = false,
                shellContextStrong = false,
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
                freshAdCandidateExists = false,
                shellContextStrong = false,
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
        assertTrue(BypassOutcomeVerifier.isExternalLanding("com.miui.home"))
        assertTrue(!BypassOutcomeVerifier.isExternalLanding("com.tencent.mm"))
        assertTrue(!BypassOutcomeVerifier.isExternalLanding("com.example.app"))
    }
}
