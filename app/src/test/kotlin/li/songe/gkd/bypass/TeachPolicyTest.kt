package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Teach policy: node rules need AGGRESSIVE, coordinate rules need CRAZY,
 * and both still require a strong ad context before acting.
 */
class TeachPolicyTest {

    private fun teachPolicy(coordinate: Boolean) = BypassRulePolicyResolver.resolveForIdentity(
        BypassRulePolicyResolver.RuleIdentity(
            subsId = li.songe.gkd.BYPASS_SPLASH_SUBS_ID,
            appId = "com.example.teachhost",
            groupKey = 9001,
            isGlobal = false,
            groupName = "教学规则 · 跳过",
            bypassMode = null,
            coordinate = coordinate,
            maxAttempts = 1,
        ),
    )

    @Test
    fun teach_node_needs_aggressive() {
        val p = teachPolicy(coordinate = false)
        assertEquals(BypassRuleTrust.TEACH_NODE, p.trust)
        assertEquals(BypassAdStrategyMode.AGGRESSIVE, p.minimumMode)
        assertTrue(p.requiresStrongAdContext)
        // Blocked in conservative.
        assertEquals(
            BypassRejectReason.RULE_LEVEL,
            rejectForMode(BypassAdStrategyMode.CONSERVATIVE, p),
        )
        // Allowed in aggressive (node candidates are gated like generic).
        assertEquals(null, rejectForMode(BypassAdStrategyMode.AGGRESSIVE, p))
    }

    @Test
    fun teach_coordinate_needs_crazy() {
        val p = teachPolicy(coordinate = true)
        assertEquals(BypassRuleTrust.TEACH_COORDINATE, p.trust)
        assertEquals(BypassAdStrategyMode.CRAZY, p.minimumMode)
        assertEquals(BypassRejectReason.RULE_LEVEL, rejectForMode(BypassAdStrategyMode.AGGRESSIVE, p))
        assertEquals(null, rejectForMode(BypassAdStrategyMode.CRAZY, p))
    }

    private fun rejectForMode(mode: BypassAdStrategyMode, policy: BypassRulePolicy): String? {
        if (mode.ordinal < policy.minimumMode.ordinal) return BypassRejectReason.RULE_LEVEL
        return BypassStrategyGate.rejectReason(
            candidate = if (policy.coordinate) BypassExitCandidateType.COORDINATE_FALLBACK
            else BypassExitCandidateType.CLOSE_TEXT,
            packageName = "com.example.app",
            activityName = "com.example.Main",
            nodeWidth = 60,
            nodeHeight = 60,
            policy = mode.policy,
            rulePolicy = policy,
            contextLevel = BypassAdContextLevel.STRONG,
            inWindow = true,
        )
    }

    @Test
    fun teach_requires_strong_context() {
        val p = teachPolicy(coordinate = false)
        assertEquals(
            BypassRejectReason.NO_AD_CONTEXT,
            BypassStrategyGate.rejectReason(
                candidate = BypassExitCandidateType.CLOSE_TEXT,
                packageName = "com.example.app",
                activityName = "com.example.Main",
                nodeWidth = 60,
                nodeHeight = 60,
                policy = BypassAdStrategyMode.AGGRESSIVE.policy,
                rulePolicy = p,
                contextLevel = BypassAdContextLevel.NONE,
                inWindow = true,
            ),
        )
    }

    @Test
    fun verification_states_are_stable() {
        assertEquals("PENDING_VERIFICATION", BypassTeachVerification.PENDING_VERIFICATION.name)
        assertEquals("VERIFIED", BypassTeachVerification.VERIFIED.name)
        assertEquals("TEST_FAILED", BypassTeachVerification.TEST_FAILED.name)
        assertFalse(BypassTeachVerification.VERIFIED.name == "TEST_FAILED")
    }
}
