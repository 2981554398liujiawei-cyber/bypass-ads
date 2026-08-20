package li.songe.gkd.bypass

import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import li.songe.gkd.data.AppRule
import li.songe.gkd.data.GlobalRule
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.ResolvedAppGroup
import li.songe.gkd.data.ResolvedGlobalGroup
import li.songe.gkd.data.SubsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layered rule stack merge: a local import must never delete unrelated
 * bundled coverage, and same-identity conflicts resolve to a recorded winner.
 */
class RuleStackManagerTest {

    private fun subscription(json: String): RawSubscription =
        RawSubscription.parse(json, json5 = false)

    private val bundledJson = """
    {
      "id": 100000001,
      "name": "bundled",
      "version": 1,
      "apps": [
        {"id": "com.app.a", "name": "AppA", "groups": [
          {"key": 1, "name": "开屏广告-A", "rules": [
            {"key": 0, "matches": ["[text=\"跳过\"]"]}
          ]},
          {"key": 2, "name": "开屏广告-B", "rules": [
            {"key": 0, "matches": ["[text=\"关闭\"]"]}
          ]}
        ]},
        {"id": "com.app.b", "name": "AppB", "groups": [
          {"key": 1, "name": "开屏广告-B", "rules": [
            {"key": 0, "matches": ["[text=\"跳过广告\"]"]}
          ]}
        ]}
      ]
    }
    """.trimIndent()

    private val localJson = """
    {
      "id": 100000001,
      "name": "local",
      "version": 1,
      "apps": [
        {"id": "com.app.a", "name": "AppA", "groups": [
          {"key": 10, "name": "开屏广告-A", "rules": [
            {"key": 0, "matches": ["[text=\"跳过\"]"]}
          ]}
        ]}
      ]
    }
    """.trimIndent()

    @Test
    fun local_import_replaces_same_group_but_keeps_other_bundled_groups() {
        val merged = BypassRuleStackManager.mergeBundledAndLocal(
            subscription(bundledJson),
            subscription(localJson),
        )
        val appA = merged.apps.first { it.id == "com.app.a" }
        val groupNames = appA.groups.map { it.name }
        // Local "开屏广告-A" replaced the bundled one; bundled "开屏广告-B" kept.
        assertEquals(setOf("开屏广告-A", "开屏广告-B"), groupNames.toSet())
        val localGroup = appA.groups.first { it.name == "开屏广告-A" }
        assertEquals(10, localGroup.key)
        // AppB untouched.
        assertTrue(merged.apps.any { it.id == "com.app.b" })
    }

    @Test
    fun import_100_apps_does_not_shrink_745_bundled_apps() {
        val bundledApps = (1..745).joinToString(",") { i ->
            """{"id": "com.bundled.$i", "name": "B$i", "groups": [{"key": 1, "name": "开屏广告", "rules": [{"key": 0, "matches": ["[text=\"跳过\"]"]}]}]}"""
        }
        val bundled = subscription("""{"id": 1, "name": "b", "version": 1, "apps": [$bundledApps]}""")
        val localApps = (1..100).joinToString(",") { i ->
            """{"id": "com.import.$i", "name": "L$i", "groups": [{"key": 1, "name": "开屏广告", "rules": [{"key": 0, "matches": ["[text=\"跳过\"]"]}]}]}"""
        }
        val local = subscription("""{"id": 1, "name": "l", "version": 1, "apps": [$localApps]}""")
        val merged = BypassRuleStackManager.mergeBundledAndLocal(bundled, local)
        assertEquals("bundled apps must not disappear", 745, merged.apps.count { it.id.startsWith("com.bundled.") })
        assertEquals(745 + 100, merged.apps.size)
    }

    @Test
    fun conflicts_are_recorded_with_winner() {
        BypassRuleStackManager.mergeBundledAndLocal(subscription(bundledJson), subscription(localJson))
        val conflict = BypassRuleStackManager.lastConflicts.firstOrNull { it.groupName == "开屏广告-A" }
        assertEquals("com.app.a", conflict?.appId)
        assertEquals("LOCAL_IMPORT", conflict?.winner)
    }

    @Test
    fun empty_local_returns_bundled_unchanged() {
        val bundled = subscription(bundledJson)
        val merged = BypassRuleStackManager.mergeBundledAndLocal(bundled, null)
        assertEquals(bundled.apps.size, merged.apps.size)
    }

    @Test
    fun local_global_groups_win_over_bundled_by_name() {
        val bundled = subscription(
            """{"id": 1, "name": "b", "version": 1, "globalGroups": [{"key": 1, "name": "开屏广告-全局", "rules": [{"key": 0, "matches": ["[text=\"跳过\"]"]}]}]}""",
        )
        val local = subscription(
            """{"id": 1, "name": "l", "version": 1, "globalGroups": [{"key": 2, "name": "开屏广告-全局", "rules": [{"key": 0, "matches": ["[text=\"关闭\"]"]}]}]}""",
        )
        val merged = BypassRuleStackManager.mergeBundledAndLocal(bundled, local)
        assertEquals(1, merged.globalGroups.size)
        assertEquals(2, merged.globalGroups.first().key)
    }

    // ---- P0-4: real import -> merge -> effective ResolvedRule -> resolver ----

    private fun subsItem() = SubsItem(id = BYPASS_SPLASH_SUBS_ID, enable = true, order = 0)

    /**
     * Real integration: parse a local import, merge it over the bundled
     * subscription exactly like [li.songe.gkd.bypass.GkdBypassEngine.importLocalRules]
     * does, then resolve the EFFECTIVE rules. The merged rules carry
     * BYPASS_SPLASH_SUBS_ID (the import rewrote their subs id), so only the
     * structured provenance side-map can tell imported from bundled rules.
     */
    @Test
    fun local_import_merged_app_rule_resolves_to_local_dedicated() {
        BypassRuleProvenance.clear()
        val bundled = subscription(bundledJson)
        val local = subscription(localJson) // com.app.a 开屏广告-A key=10
        val merged = BypassRuleStackManager.mergeBundledAndLocal(bundled, local)

        // The imported group (key 10, carried under BYPASS_SPLASH_SUBS_ID):
        val app = merged.apps.first { it.id == "com.app.a" }
        val importedGroup = app.groups.first { it.key == 10 }
        val resolvedGroup = ResolvedAppGroup(
            importedGroup,
            merged,
            subsItem(),
            config = null,
            app = app,
            enable = true,
        )
        val rule = AppRule(
            importedGroup.rules.first() as RawSubscription.RawAppRule,
            resolvedGroup,
            appInfo = null,
        )
        val policy = BypassRulePolicyResolver.resolve(rule)
        assertEquals("imported rule must stay LOCAL_IMPORT_DEDICATED after merge", BypassRuleTrust.LOCAL_IMPORT_DEDICATED, policy.trust)
        assertEquals(BypassAdStrategyMode.AGGRESSIVE, policy.minimumMode)
        assertTrue(policy.requiresStrongAdContext)
    }

    @Test
    fun bundled_group_kept_by_merge_still_resolves_to_bundled_dedicated() {
        BypassRuleProvenance.clear()
        val bundled = subscription(bundledJson)
        val local = subscription(localJson)
        val merged = BypassRuleStackManager.mergeBundledAndLocal(bundled, local)

        // The bundled group that the import did NOT touch (key 2) must keep
        // its bundled identity — the side-map must never over-mark.
        val app = merged.apps.first { it.id == "com.app.a" }
        val keptGroup = app.groups.first { it.key == 2 }
        val resolvedGroup = ResolvedAppGroup(keptGroup, merged, subsItem(), config = null, app = app, enable = true)
        val rule = AppRule(keptGroup.rules.first() as RawSubscription.RawAppRule, resolvedGroup, appInfo = null)
        assertEquals(BypassRuleTrust.BUNDLED_DEDICATED, BypassRulePolicyResolver.resolve(rule).trust)
    }

    @Test
    fun local_import_merged_global_rule_resolves_to_local_global() {
        BypassRuleProvenance.clear()
        val bundled = subscription(
            """{"id": 1, "name": "b", "version": 1, "globalGroups": [{"key": 1, "name": "开屏广告-全局", "rules": [{"key": 0, "matches": ["[text=\"跳过\"]"]}]}]}""",
        )
        val local = subscription(
            """{"id": 1, "name": "l", "version": 1, "globalGroups": [{"key": 2, "name": "开屏广告-全局", "rules": [{"key": 0, "matches": ["[text=\"关闭\"]"]}]}]}""",
        )
        val merged = BypassRuleStackManager.mergeBundledAndLocal(bundled, local)

        val importedGlobal = merged.globalGroups.first { it.key == 2 }
        val resolvedGroup = ResolvedGlobalGroup(importedGlobal, merged, subsItem(), config = null)
        val rule = GlobalRule(
            importedGlobal.rules.first() as RawSubscription.RawGlobalRule,
            resolvedGroup,
            appInfoCache = emptyMap(),
        )
        val policy = BypassRulePolicyResolver.resolve(rule)
        assertEquals("imported global rule must stay LOCAL_IMPORT_GLOBAL after merge", BypassRuleTrust.LOCAL_IMPORT_GLOBAL, policy.trust)
        assertEquals(BypassAdStrategyMode.AGGRESSIVE, policy.minimumMode)
    }

    @Test
    fun clearing_local_import_clears_provenance() {
        BypassRuleProvenance.clear()
        val bundled = subscription(bundledJson)
        val local = subscription(localJson)
        BypassRuleStackManager.mergeBundledAndLocal(bundled, local)
        assertTrue(BypassRuleProvenance.isLocalAppGroup("com.app.a", 10))

        BypassRuleProvenance.clear()
        assertTrue(!BypassRuleProvenance.isLocalAppGroup("com.app.a", 10))
    }
}
