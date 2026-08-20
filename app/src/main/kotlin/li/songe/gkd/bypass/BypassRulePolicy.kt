package li.songe.gkd.bypass

import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import li.songe.gkd.data.AppRule
import li.songe.gkd.data.GlobalRule
import li.songe.gkd.data.ResolvedRule

/**
 * Where a rule came from. This is the security boundary: the strategy mode
 * a rule may run under is derived from its origin and its explicit
 * [RawRuleProps.bypassMode] metadata, never from parsing its display name.
 */
enum class BypassRuleTrust {
    /** Curated app-specific ad rules bundled with the mature source. */
    BUNDLED_DEDICATED,

    /** Curated global ad rules bundled with the mature source. */
    BUNDLED_GLOBAL,

    /** Bypass Ads official overrides (WeChat / Alipay / testad). */
    BYPASS_OVERRIDE,

    /** User-imported app-specific rules. */
    LOCAL_IMPORT_DEDICATED,

    /** User-imported global rules. */
    LOCAL_IMPORT_GLOBAL,

    /** User-taught node rule. */
    TEACH_NODE,

    /** User-taught coordinate rule. */
    TEACH_COORDINATE,
}

/**
 * Strategy policy derived from rule identity (subsId + group type + appId +
 * groupKey + ruleKey) plus the explicit [bypassMode] metadata.
 */
data class BypassRulePolicy(
    val trust: BypassRuleTrust,
    /** Minimum strategy mode that may run this rule. */
    val minimumMode: BypassAdStrategyMode,
    /** Generic close/glyph/structural candidates need STRONG ad context. */
    val requiresStrongAdContext: Boolean,
    /** Whether this rule is a coordinate fallback. */
    val coordinate: Boolean,
    /** Rule-level action cap; the session hard cap is min(this, 3). */
    val maxAttempts: Int,
)

/**
 * Resolves the policy of a matched rule from its identity, not its name.
 *
 * Rule names (e.g. "...-Aggressive") are kept for log readability only and
 * are never treated as a security boundary.
 */
object BypassRulePolicyResolver {

    private val teachGroupPrefix = "教学规则"

    /** Identity inputs the resolver needs (pure, unit-testable). */
    data class RuleIdentity(
        val subsId: Long,
        val isGlobal: Boolean,
        val groupName: String,
        val bypassMode: String?,
        val coordinate: Boolean,
        val maxAttempts: Int,
    )

    fun resolve(rule: ResolvedRule): BypassRulePolicy = resolveForIdentity(
        RuleIdentity(
            subsId = rule.subsItem.id,
            isGlobal = rule is GlobalRule,
            groupName = rule.g.group.name,
            bypassMode = rule.rule.bypassMode,
            coordinate = rule.rule.position != null,
            maxAttempts = (rule.rule.actionMaximum ?: 3).coerceIn(1, 3),
        ),
    )

    /** Pure policy resolution (used by tests and [resolve]). */
    fun resolveForIdentity(identity: RuleIdentity): BypassRulePolicy {
        val trust = trustOf(identity)
        val minimumMode = minimumModeOf(trust, identity.bypassMode, identity.coordinate)
        val requiresStrongAdContext = when (trust) {
            BypassRuleTrust.BUNDLED_DEDICATED -> false
            // Global skip rules are curated; global generic close/glyph rules
            // still need ad context to fire (they are gated per candidate).
            BypassRuleTrust.BUNDLED_GLOBAL -> true
            BypassRuleTrust.BYPASS_OVERRIDE -> true
            BypassRuleTrust.LOCAL_IMPORT_DEDICATED -> true
            BypassRuleTrust.LOCAL_IMPORT_GLOBAL -> true
            BypassRuleTrust.TEACH_NODE -> true
            BypassRuleTrust.TEACH_COORDINATE -> true
        }
        return BypassRulePolicy(
            trust = trust,
            minimumMode = minimumMode,
            requiresStrongAdContext = requiresStrongAdContext,
            coordinate = identity.coordinate,
            maxAttempts = identity.maxAttempts.coerceIn(1, 3),
        )
    }

    private fun trustOf(identity: RuleIdentity): BypassRuleTrust {
        val inBypassSubs = identity.subsId == BYPASS_SPLASH_SUBS_ID
        return when {
            !inBypassSubs && identity.isGlobal -> BypassRuleTrust.LOCAL_IMPORT_GLOBAL
            !inBypassSubs -> BypassRuleTrust.LOCAL_IMPORT_DEDICATED
            identity.isGlobal -> BypassRuleTrust.BUNDLED_GLOBAL
            else -> when {
                identity.groupName.startsWith(teachGroupPrefix) -> {
                    if (identity.coordinate) BypassRuleTrust.TEACH_COORDINATE
                    else BypassRuleTrust.TEACH_NODE
                }
                identity.bypassMode != null -> BypassRuleTrust.BYPASS_OVERRIDE
                else -> BypassRuleTrust.BUNDLED_DEDICATED
            }
        }
    }

    private fun minimumModeOf(
        trust: BypassRuleTrust,
        bypassMode: String?,
        coordinate: Boolean,
    ): BypassAdStrategyMode = when (trust) {
        BypassRuleTrust.BUNDLED_DEDICATED -> BypassAdStrategyMode.CONSERVATIVE
        BypassRuleTrust.BUNDLED_GLOBAL -> modeFromMetadata(bypassMode)
        BypassRuleTrust.BYPASS_OVERRIDE -> modeFromMetadata(bypassMode)
        BypassRuleTrust.LOCAL_IMPORT_GLOBAL -> BypassAdStrategyMode.AGGRESSIVE
        BypassRuleTrust.LOCAL_IMPORT_DEDICATED ->
            if (coordinate) BypassAdStrategyMode.CRAZY else BypassAdStrategyMode.AGGRESSIVE
        BypassRuleTrust.TEACH_NODE -> BypassAdStrategyMode.AGGRESSIVE
        BypassRuleTrust.TEACH_COORDINATE -> BypassAdStrategyMode.CRAZY
    }

    private fun modeFromMetadata(bypassMode: String?): BypassAdStrategyMode = when (bypassMode?.uppercase()) {
        "AGGRESSIVE" -> BypassAdStrategyMode.AGGRESSIVE
        "CRAZY" -> BypassAdStrategyMode.CRAZY
        else -> BypassAdStrategyMode.CONSERVATIVE
    }
}
