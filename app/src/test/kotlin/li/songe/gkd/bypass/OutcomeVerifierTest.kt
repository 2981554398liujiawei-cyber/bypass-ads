package li.songe.gkd.bypass

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Outcome decision logic (pure part of BypassOutcomeVerifier):
 * ActionResult=true is NOT success; only fresh-state verification is.
 *
 * Mini-program shells are deliberately NOT ad proof: after the ad closes,
 * WeChat keeps the mini-program running inside AppBrandUI, so "AppBrandUI is
 * still foreground" must not force ACTION_NO_EFFECT (P0-2).
 */
class OutcomeVerifierTest {

    @Test
    fun action_accepted_but_ad_candidate_remains_is_not_success() {
        assertEquals(
            BypassOutcome.ACTION_NO_EFFECT,
            BypassOutcomeVerifier.decide(
                freshAdCandidateExists = true,
                freshAdEvidence = false,
                topChanged = false,
                topChangedToExternal = false,
            ),
        )
    }

    @Test
    fun fresh_ad_label_still_visible_is_not_success() {
        // An ad overlay still showing its 广告/ad label after the action means
        // the ad is still up, even when no exit candidate re-matched.
        assertEquals(
            BypassOutcome.ACTION_NO_EFFECT,
            BypassOutcomeVerifier.decide(
                freshAdCandidateExists = false,
                freshAdEvidence = true,
                topChanged = false,
                topChangedToExternal = false,
            ),
        )
    }

    @Test
    fun wechat_appbrandui_without_ad_evidence_is_success() {
        // P0-2 acceptance: before the action an ad candidate exists inside
        // AppBrandUI; after the action the SAME AppBrandUI shows no ad
        // candidate and no ad label -> the ad closed, the mini-program is
        // simply still running -> SUCCESS_CONFIRMED.
        assertEquals(
            BypassOutcome.SUCCESS_CONFIRMED,
            BypassOutcomeVerifier.decide(
                freshAdCandidateExists = false,
                freshAdEvidence = false,
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
                freshAdEvidence = false,
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
                freshAdEvidence = false,
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

    // ---- P0-5: the verifier must re-read the top app AFTER its delay ----

    @Test
    fun action_true_then_late_chrome_jump_is_misclick_even_with_no_window() = runBlocking {
        // performAction returns true -> 200ms later the app jumps to Chrome
        // -> at the 300ms verifier the fresh top read sees Chrome. The cached
        // pre-delay top state (WeChat) must NOT win; without fresh evidence
        // of Chrome the verdict would be SUCCESS_CONFIRMED, which is banned.
        val outcome = BypassOutcomeVerifier.verify(
            packageName = "com.tencent.mm",
            freshWindowProvider = { null }, // window read unavailable
            topFallbackProvider = { "com.android.chrome" }, // fresh top AFTER delay
            freshAdCandidateProvider = { false },
            freshAdLabelProvider = { false },
        )
        assertEquals(BypassOutcome.MISCLICK_SUSPECTED, outcome)
    }

    @Test
    fun late_jump_to_unknown_app_is_unresolved_not_success() = runBlocking {
        val outcome = BypassOutcomeVerifier.verify(
            packageName = "com.tencent.mm",
            freshWindowProvider = { null },
            topFallbackProvider = { "com.other.app" },
            freshAdCandidateProvider = { false },
            freshAdLabelProvider = { false },
        )
        assertEquals(BypassOutcome.UNRESOLVED, outcome)
    }

    @Test
    fun no_window_and_unchanged_top_is_unresolved() = runBlocking {
        val outcome = BypassOutcomeVerifier.verify(
            packageName = "com.tencent.mm",
            freshWindowProvider = { null },
            topFallbackProvider = { "com.tencent.mm" },
            freshAdCandidateProvider = { false },
            freshAdLabelProvider = { false },
        )
        assertEquals(BypassOutcome.UNRESOLVED, outcome)
    }
}
