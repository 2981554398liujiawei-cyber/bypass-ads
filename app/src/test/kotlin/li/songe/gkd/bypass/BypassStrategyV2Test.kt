package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strategy V2 layering and one-ad-one-session result semantics:
 * attempts accumulate inside ONE session; the session counts once.
 */
class BypassStrategyV2Test {

    @Test
    fun aggressive_and_crazy_discovery_sets_are_distinct() {
        val aggressive = BypassAdStrategyMode.AGGRESSIVE.policy
        val crazy = BypassAdStrategyMode.CRAZY.policy
        // The two capabilities that ONLY crazy has:
        assertFalse(aggressive.allowStructuralNoSemanticClose)
        assertFalse(aggressive.allowCoordinateFallback)
        assertTrue(crazy.allowStructuralNoSemanticClose)
        assertTrue(crazy.allowCoordinateFallback)
        // Both share explicit close / view-id / glyph:
        assertTrue(aggressive.allowGenericCloseText)
        assertTrue(aggressive.allowGlyphClose)
    }

    @Test
    fun mature_dedicated_close_is_allowed_in_conservative() {
        // Card PART V matrix: mature dedicated Close -> YES in conservative.
        val reject = BypassStrategyGate.rejectReason(
            candidate = BypassExitCandidateType.CLOSE_TEXT,
            packageName = "com.example.app",
            activityName = "com.example.Main",
            nodeWidth = 80,
            nodeHeight = 40,
            policy = BypassAdStrategyMode.CONSERVATIVE.policy,
            rulePolicy = BypassRulePolicy(
                trust = BypassRuleTrust.BUNDLED_DEDICATED,
                minimumMode = BypassAdStrategyMode.CONSERVATIVE,
                requiresStrongAdContext = false,
                coordinate = false,
                maxAttempts = 3,
            ),
            contextLevel = BypassAdContextLevel.NONE,
            inWindow = false,
        )
        assertEquals(null, reject)
    }

    @Test
    fun generic_close_in_conservative_is_rejected() {
        val reject = BypassStrategyGate.rejectReason(
            candidate = BypassExitCandidateType.CLOSE_TEXT,
            packageName = "com.example.app",
            activityName = "com.example.Main",
            nodeWidth = 80,
            nodeHeight = 40,
            policy = BypassAdStrategyMode.CONSERVATIVE.policy,
            rulePolicy = BypassRulePolicy(
                trust = BypassRuleTrust.BUNDLED_GLOBAL,
                minimumMode = BypassAdStrategyMode.CONSERVATIVE,
                requiresStrongAdContext = true,
                coordinate = false,
                maxAttempts = 3,
            ),
            contextLevel = BypassAdContextLevel.STRONG,
            inWindow = true,
        )
        assertEquals(BypassRejectReason.STRATEGY_GATE, reject)
    }

    @Test
    fun structural_close_requires_crazy_even_in_strong_context() {
        for (mode in listOf(BypassAdStrategyMode.CONSERVATIVE, BypassAdStrategyMode.AGGRESSIVE)) {
            val reject = BypassStrategyGate.rejectReason(
                candidate = BypassExitCandidateType.STRUCTURAL_CLOSE,
                packageName = "com.example.app",
                activityName = "com.example.Main",
                nodeWidth = 60,
                nodeHeight = 60,
                policy = mode.policy,
                rulePolicy = BypassRulePolicy(
                    trust = BypassRuleTrust.BUNDLED_GLOBAL,
                    minimumMode = BypassAdStrategyMode.CONSERVATIVE,
                    requiresStrongAdContext = true,
                    coordinate = false,
                    maxAttempts = 3,
                ),
                contextLevel = BypassAdContextLevel.STRONG,
                inWindow = true,
            )
            assertEquals("structural rejected in $mode", BypassRejectReason.STRATEGY_GATE, reject)
        }
        assertEquals(
            null,
            BypassStrategyGate.rejectReason(
                candidate = BypassExitCandidateType.STRUCTURAL_CLOSE,
                packageName = "com.example.app",
                activityName = "com.example.Main",
                nodeWidth = 60,
                nodeHeight = 60,
                policy = BypassAdStrategyMode.CRAZY.policy,
                rulePolicy = BypassRulePolicy(
                    trust = BypassRuleTrust.BUNDLED_GLOBAL,
                    minimumMode = BypassAdStrategyMode.CONSERVATIVE,
                    requiresStrongAdContext = true,
                    coordinate = false,
                    maxAttempts = 3,
                ),
                contextLevel = BypassAdContextLevel.STRONG,
                inWindow = true,
            ),
        )
    }

    @Test
    fun session_result_names_are_stable() {
        assertEquals(5, BypassSessionResult.entries.size)
        assertEquals("SUCCESS_CONFIRMED", BypassSessionResult.SUCCESS_CONFIRMED.name)
        assertEquals("FAILURE_CONFIRMED", BypassSessionResult.FAILURE_CONFIRMED.name)
        assertEquals("MISCLICK_SUSPECTED", BypassSessionResult.MISCLICK_SUSPECTED.name)
        assertEquals("UNRESOLVED", BypassSessionResult.UNRESOLVED.name)
        assertEquals("OPEN", BypassSessionResult.OPEN.name)
    }
}
