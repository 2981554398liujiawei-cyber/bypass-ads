package li.songe.gkd.bypass

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session-scoped outcome decision logic (P0-2): after an action the verifier
 * re-checks THE SAME ad region/exit the session acted on — never the whole
 * window. Mini-program shells are deliberately NOT ad proof: after the ad
 * closes, WeChat keeps the mini-program running inside AppBrandUI, and an
 * unrelated page banner (瓜子/GNC) is not "the ad".
 */
class OutcomeVerifierTest {

    // ---- pure decision matrix ----

    @Test
    fun same_ad_candidate_remaining_is_not_success() {
        // Acceptance: same splash candidate remains => ACTION_NO_EFFECT.
        assertEquals(
            BypassOutcome.ACTION_NO_EFFECT,
            decide(sameAdExists = true, proximityAdLabel = false),
        )
    }

    @Test
    fun ad_label_near_acted_candidate_is_not_success() {
        assertEquals(
            BypassOutcome.ACTION_NO_EFFECT,
            decide(sameAdExists = false, proximityAdLabel = true),
        )
    }

    @Test
    fun same_appbrandui_without_same_ad_evidence_is_success() {
        // Acceptance: same AppBrandUI + no same-ad evidence => SUCCESS.
        // The mini-program is still running; the ad itself is gone.
        assertEquals(
            BypassOutcome.SUCCESS_CONFIRMED,
            decide(sameAdExists = false, proximityAdLabel = false),
        )
    }

    @Test
    fun unrelated_banner_does_not_block_success() {
        // Acceptance: same AppBrandUI + unrelated banner "广告" (far from the
        // acted candidate) => SUCCESS_CONFIRMED (star-charge fixture).
        assertEquals(
            BypassOutcome.SUCCESS_CONFIRMED,
            decide(sameAdExists = false, proximityAdLabel = false),
        )
    }

    @Test
    fun unrelated_small_imageview_does_not_block_success() {
        // Acceptance: same AppBrandUI + unrelated small ImageView that would
        // match a Crazy structural selector => SUCCESS_CONFIRMED. The
        // verifier only re-checks the ACTED rule family/region (P0-2).
        assertEquals(
            BypassOutcome.SUCCESS_CONFIRMED,
            decide(sameAdExists = false, proximityAdLabel = false),
        )
    }

    @Test
    fun external_landing_is_misclick_and_stops_immediately() {
        // Acceptance: external Chrome => MISCLICK.
        assertEquals(
            BypassOutcome.MISCLICK_SUSPECTED,
            BypassOutcomeVerifier.decide(
                sameAdExists = false,
                proximityAdLabel = false,
                topChanged = true,
                topChangedToExternal = true,
                hasSessionEvidence = true,
            ),
        )
    }

    @Test
    fun unknown_top_change_is_unresolved() {
        assertEquals(
            BypassOutcome.UNRESOLVED,
            BypassOutcomeVerifier.decide(
                sameAdExists = false,
                proximityAdLabel = false,
                topChanged = true,
                topChangedToExternal = false,
                hasSessionEvidence = true,
            ),
        )
    }

    @Test
    fun missing_session_evidence_is_unresolved() {
        // Without the session's acted evidence there is nothing to verify:
        // never a guessed success or failure.
        assertEquals(
            BypassOutcome.UNRESOLVED,
            BypassOutcomeVerifier.decide(
                sameAdExists = false,
                proximityAdLabel = false,
                topChanged = false,
                topChangedToExternal = false,
                hasSessionEvidence = false,
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

    // ---- bounds math (pure, JVM) ----

    @Test
    fun same_ad_region_uses_center_distance() {
        val splash = Bounds(972, 192, 1152, 265)          // top-right splash skip
        assertTrue(BypassOutcomeVerifier.sameAdRegion(splash, Bounds(970, 190, 1150, 270)))
        // 瓜子 banner label at [78,1221] and GNC at [93,1890] are far away.
        assertTrue(!BypassOutcomeVerifier.sameAdRegion(splash, Bounds(78, 1221, 108, 1245)))
        assertTrue(!BypassOutcomeVerifier.sameAdRegion(splash, Bounds(93, 1890, 120, 1910)))
    }

    @Test
    fun parse_bounds_handles_malformed_input() {
        assertEquals(Bounds(1, 2, 3, 4), BypassOutcomeVerifier.parseBounds("1,2,3,4"))
        assertEquals(null, BypassOutcomeVerifier.parseBounds(null))
        assertEquals(null, BypassOutcomeVerifier.parseBounds("1,2,3"))
        assertEquals(null, BypassOutcomeVerifier.parseBounds("a,b,c,d"))
    }

    // ---- verify() integration (fresh providers, session evidence) ----

    /** Convenience: full-fresh-path decision helper. */
    private fun decide(sameAdExists: Boolean, proximityAdLabel: Boolean): BypassOutcome =
        BypassOutcomeVerifier.decide(
            sameAdExists = sameAdExists,
            proximityAdLabel = proximityAdLabel,
            topChanged = false,
            topChangedToExternal = false,
            hasSessionEvidence = true,
        )

    private val splashEvidence = BypassSessionAdEvidence(
        candidateType = BypassExitCandidateType.SKIP_TEXT,
        ruleKey = 200,
        groupKey = 200,
        bounds = "972,192,1152,265",
    )

    @Test
    fun action_true_then_late_chrome_jump_is_misclick_even_with_no_window() = runBlocking {
        val outcome = BypassOutcomeVerifier.verify(
            packageName = "com.tencent.mm",
            sessionEvidence = splashEvidence,
            freshWindowProvider = { null }, // window read unavailable
            topFallbackProvider = { "com.android.chrome" }, // fresh top AFTER delay
            freshSameAdProvider = { _, _ -> false },
            freshProximityAdLabelProvider = { _, _ -> false },
        )
        assertEquals(BypassOutcome.MISCLICK_SUSPECTED, outcome)
    }

    @Test
    fun late_jump_to_unknown_app_is_unresolved_not_success() = runBlocking {
        val outcome = BypassOutcomeVerifier.verify(
            packageName = "com.tencent.mm",
            sessionEvidence = splashEvidence,
            freshWindowProvider = { null },
            topFallbackProvider = { "com.other.app" },
            freshSameAdProvider = { _, _ -> false },
            freshProximityAdLabelProvider = { _, _ -> false },
        )
        assertEquals(BypassOutcome.UNRESOLVED, outcome)
    }

    @Test
    fun fresh_root_unavailable_is_unresolved() = runBlocking {
        // Acceptance: fresh root unavailable => UNRESOLVED.
        val outcome = BypassOutcomeVerifier.verify(
            packageName = "com.tencent.mm",
            sessionEvidence = splashEvidence,
            freshWindowProvider = { null },
            topFallbackProvider = { "com.tencent.mm" },
            freshSameAdProvider = { _, _ -> false },
            freshProximityAdLabelProvider = { _, _ -> false },
        )
        assertEquals(BypassOutcome.UNRESOLVED, outcome)
    }

    @Test
    fun no_session_evidence_is_unresolved() = runBlocking {
        val outcome = BypassOutcomeVerifier.verify(
            packageName = "com.tencent.mm",
            sessionEvidence = null,
            freshWindowProvider = { null },
            topFallbackProvider = { "com.tencent.mm" },
            freshSameAdProvider = { _, _ -> false },
            freshProximityAdLabelProvider = { _, _ -> false },
        )
        assertEquals(BypassOutcome.UNRESOLVED, outcome)
    }

    @Test
    fun star_charge_splash_closed_lands_on_normal_page_with_banners_is_success() {
        // STAR-CHARGE REGRESSION fixture: before = AppBrandUI + splash Skip
        // candidate; after the action the same AppBrandUI shows the normal
        // page (瓜子/GNC banners with 广告 labels far from the acted
        // candidate). The splash candidate is gone => SUCCESS_CONFIRMED,
        // never ACTION_NO_EFFECT, never a follow-up click on the banner.
        // This is the verify() decision chain (P0-2): same app + fresh root +
        // same-ad gone + no proximity label.
        assertEquals(
            BypassOutcome.SUCCESS_CONFIRMED,
            BypassOutcomeVerifier.decideFromVerify(
                freshTopPkg = "com.tencent.mm",
                packageName = "com.tencent.mm",
                freshRootAvailable = true,
                sessionEvidenceAvailable = true,
                sameAdExists = false,
                proximityAdLabel = false, // 瓜子/GNC banners are > SAME_AD_RADIUS away
            ),
        )
    }

    @Test
    fun splash_candidate_remaining_same_region_is_action_no_effect() {
        // Acceptance: same splash candidate remains => ACTION_NO_EFFECT.
        assertEquals(
            BypassOutcome.ACTION_NO_EFFECT,
            BypassOutcomeVerifier.decideFromVerify(
                freshTopPkg = "com.tencent.mm",
                packageName = "com.tencent.mm",
                freshRootAvailable = true,
                sessionEvidenceAvailable = true,
                sameAdExists = true,
                proximityAdLabel = false,
            ),
        )
    }
}
