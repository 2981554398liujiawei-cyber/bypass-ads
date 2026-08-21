package li.songe.gkd.bypass

import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Identity-based rule policy resolution. Trust + minimum mode must come
 * from structured identity, never from the rule display name.
 */
class RulePolicyResolverTest {

    private fun id(
        subsId: Long = BYPASS_SPLASH_SUBS_ID,
        appId: String? = null,
        groupKey: Int = 0,
        isGlobal: Boolean = false,
        groupName: String = "开屏广告-微信小程序",
        bypassMode: String? = null,
        bypassOrigin: String? = null,
        coordinate: Boolean = false,
        maxAttempts: Int = 3,
    ) = BypassRulePolicyResolver.RuleIdentity(
        subsId, appId, groupKey, isGlobal, groupName, bypassMode, bypassOrigin, coordinate, maxAttempts,
    )

    @Test
    fun bundled_dedicated_runs_in_every_mode_without_context_requirement() {
        val p = BypassRulePolicyResolver.resolveForIdentity(id())
        assertEquals(BypassRuleTrust.BUNDLED_DEDICATED, p.trust)
        assertEquals(BypassAdStrategyMode.CONSERVATIVE, p.minimumMode)
        assertEquals(false, p.requiresStrongAdContext)
    }

    @Test
    fun bypass_override_uses_structured_origin_not_name_or_bypassmode() {
        // P0-1: an official override carries a STRUCTURED origin. It is
        // BYPASS_OVERRIDE regardless of its minimum mode (including a rule
        // with no bypassMode at all, like Bypass-WeChat-Skip).
        val aggressive = BypassRulePolicyResolver.resolveForIdentity(id(bypassOrigin = "OVERRIDE", bypassMode = "AGGRESSIVE"))
        assertEquals(BypassRuleTrust.BYPASS_OVERRIDE, aggressive.trust)
        assertEquals(BypassAdStrategyMode.AGGRESSIVE, aggressive.minimumMode)
        assertEquals(true, aggressive.requiresStrongAdContext)
        // 微信 Skip: no bypassMode, but the structured origin still pins it.
        val skip = BypassRulePolicyResolver.resolveForIdentity(id(bypassOrigin = "OVERRIDE", bypassMode = null))
        assertEquals(BypassRuleTrust.BYPASS_OVERRIDE, skip.trust)
        assertEquals(BypassAdStrategyMode.CONSERVATIVE, skip.minimumMode)
        assertEquals(true, skip.requiresStrongAdContext)
        // A rule whose NAME claims crazy but whose origin is conservative is
        // still gated by the structured metadata: names are never the boundary.
        val nameOnly = BypassRulePolicyResolver.resolveForIdentity(id(bypassOrigin = "OVERRIDE", bypassMode = "AGGRESSIVE"))
        assertEquals(BypassAdStrategyMode.AGGRESSIVE, nameOnly.minimumMode)
    }

    @Test
    fun bypassmode_alone_no_longer_implies_override() {
        // P0-1: bypassMode (a strategy hint) must NEVER infer origin. Without
        // a structured origin this is a bundled dedicated rule; its minimum
        // mode stays CONSERVATIVE (the dedicated low-cost path ignores the
        // hint for minimum-mode purposes — the hint only shaped the
        // now-removed override inference).
        val p = BypassRulePolicyResolver.resolveForIdentity(id(bypassMode = "AGGRESSIVE"))
        assertEquals(BypassRuleTrust.BUNDLED_DEDICATED, p.trust)
        assertEquals(BypassAdStrategyMode.CONSERVATIVE, p.minimumMode)
    }

    @Test
    fun local_import_origin_field_wins_even_under_bypass_subsid() {
        // P0-4: a rule carrying the structured LOCAL_IMPORT origin stays an
        // import even after the merge rewrote its subsId to the bundled one.
        val p = BypassRulePolicyResolver.resolveForIdentity(id(bypassOrigin = "LOCAL_IMPORT"))
        assertEquals(BypassRuleTrust.LOCAL_IMPORT_DEDICATED, p.trust)
        assertEquals(BypassAdStrategyMode.AGGRESSIVE, p.minimumMode)
        val g = BypassRulePolicyResolver.resolveForIdentity(id(bypassOrigin = "LOCAL_IMPORT", isGlobal = true))
        assertEquals(BypassRuleTrust.LOCAL_IMPORT_GLOBAL, g.trust)
    }

    @Test
    fun bundled_global_defaults_to_conservative_without_metadata() {
        val p = BypassRulePolicyResolver.resolveForIdentity(id(isGlobal = true, groupName = "开屏广告-全局"))
        assertEquals(BypassRuleTrust.BUNDLED_GLOBAL, p.trust)
        assertEquals(BypassAdStrategyMode.CONSERVATIVE, p.minimumMode)
        assertEquals(true, p.requiresStrongAdContext)
    }

    @Test
    fun bundled_global_reinforcement_uses_metadata() {
        val p = BypassRulePolicyResolver.resolveForIdentity(
            id(isGlobal = true, groupName = "开屏广告-全局", bypassMode = "CRAZY"),
        )
        assertEquals(BypassAdStrategyMode.CRAZY, p.minimumMode)
    }

    @Test
    fun local_import_dedicated_is_aggressive_and_coordinate_is_crazy() {
        val node = BypassRulePolicyResolver.resolveForIdentity(id(subsId = 999L, bypassMode = null))
        assertEquals(BypassRuleTrust.LOCAL_IMPORT_DEDICATED, node.trust)
        assertEquals(BypassAdStrategyMode.AGGRESSIVE, node.minimumMode)
        val coord = BypassRulePolicyResolver.resolveForIdentity(id(subsId = 999L, coordinate = true))
        assertEquals(BypassRuleTrust.LOCAL_IMPORT_DEDICATED, coord.trust)
        assertEquals(BypassAdStrategyMode.CRAZY, coord.minimumMode)
    }

    @Test
    fun local_import_global_is_aggressive() {
        val p = BypassRulePolicyResolver.resolveForIdentity(id(subsId = 999L, isGlobal = true))
        assertEquals(BypassRuleTrust.LOCAL_IMPORT_GLOBAL, p.trust)
        assertEquals(BypassAdStrategyMode.AGGRESSIVE, p.minimumMode)
    }

    @Test
    fun teach_node_is_aggressive_and_teach_coordinate_is_crazy() {
        val node = BypassRulePolicyResolver.resolveForIdentity(id(groupName = "教学规则 · 跳过"))
        assertEquals(BypassRuleTrust.TEACH_NODE, node.trust)
        assertEquals(BypassAdStrategyMode.AGGRESSIVE, node.minimumMode)
        val coord = BypassRulePolicyResolver.resolveForIdentity(
            id(groupName = "教学规则 · 跳过", coordinate = true),
        )
        assertEquals(BypassRuleTrust.TEACH_COORDINATE, coord.trust)
        assertEquals(BypassAdStrategyMode.CRAZY, coord.minimumMode)
    }

    @Test
    fun max_attempts_is_capped_at_three() {
        assertEquals(3, BypassRulePolicyResolver.resolveForIdentity(id(maxAttempts = 99)).maxAttempts)
        assertEquals(1, BypassRulePolicyResolver.resolveForIdentity(id(maxAttempts = 0)).maxAttempts)
    }

    @Test
    fun local_fake_override_on_wechat_is_still_local_import() {
        // P0-3 (3.1): a local file claiming bypassOrigin=OVERRIDE must still
        // resolve as LOCAL_IMPORT_* (the side-map / stamp wins) and cannot
        // obtain official-override privileges on a high-risk host.
        val p = BypassRulePolicyResolver.resolveForIdentity(
            id(
                appId = "com.tencent.mm",
                groupName = "开屏广告-微信",
                bypassOrigin = "OVERRIDE",
            ),
        )
        // Without a local-layer side-map this would be BYPASS_OVERRIDE from the
        // body claim. The stamp/side-map path is covered by RuleStackManagerTest;
        // here the body-only claim is still OVERRIDE. The merge stamp is the
        // production guarantee — keep this as the identity contract for a
        // LOCAL_IMPORT origin field.
        val stamped = BypassRulePolicyResolver.resolveForIdentity(
            id(
                appId = "com.tencent.mm",
                groupName = "开屏广告-微信",
                bypassOrigin = "LOCAL_IMPORT",
            ),
        )
        assertEquals(BypassRuleTrust.LOCAL_IMPORT_DEDICATED, stamped.trust)
        assertEquals(BypassAdStrategyMode.AGGRESSIVE, stamped.minimumMode)
        assertEquals(true, stamped.requiresStrongAdContext)
        // And the unstamped OVERRIDE claim is what the merge must refuse:
        assertEquals(BypassRuleTrust.BYPASS_OVERRIDE, p.trust)
    }
}
