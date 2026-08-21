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

    // ---- P0-4: groupKey collision (bundled app=A key=10 name=X vs
    // ---- local app=A key=10 name=Y) ----

    private val bundledCollisionJson = """
    {
      "id": 100000001,
      "name": "bundled",
      "version": 1,
      "apps": [
        {"id": "com.app.a", "name": "AppA", "groups": [
          {"key": 10, "name": "开屏广告-X", "rules": [
            {"key": 0, "matches": ["[text=\"跳过\"]"]}
          ]}
        ]}
      ]
    }
    """.trimIndent()

    private val localCollisionJson = """
    {
      "id": 100000001,
      "name": "local",
      "version": 1,
      "apps": [
        {"id": "com.app.a", "name": "AppA", "groups": [
          {"key": 10, "name": "开屏广告-Y", "rules": [
            {"key": 0, "matches": ["[text=\"关闭\"]"]}
          ]}
        ]}
      ]
    }
    """.trimIndent()

    @Test
    fun same_key_different_name_collision_is_remapped_not_shared() {
        BypassRuleProvenance.clear()
        val bundled = subscription(bundledCollisionJson)
        val local = subscription(localCollisionJson)
        val merged = BypassRuleStackManager.mergeBundledAndLocal(bundled, local)

        // Both groups survive with DIFFERENT keys: never two groups sharing
        // one DB identity (appId + groupKey).
        val app = merged.apps.first { it.id == "com.app.a" }
        assertEquals(2, app.groups.size)
        val bundledGroup = app.groups.first { it.name == "开屏广告-X" }
        val localGroup = app.groups.first { it.name == "开屏广告-Y" }
        assertEquals(10, bundledGroup.key)
        assertTrue("local key must be remapped", localGroup.key != 10)

        // Conflict recorded with remap info.
        val conflict = BypassRuleStackManager.lastConflicts.first {
            it.groupName == "开屏广告-Y" && it.winner == "LOCAL_IMPORT_REMAPPED"
        }
        assertEquals("LOCAL_IMPORT_REMAPPED", conflict.winner)
        assertEquals(10, conflict.remappedFromKey)

        // The bundled group sharing the OLD key is NOT mis-tagged as local.
        val bResolved = ResolvedAppGroup(bundledGroup, merged, subsItem(), config = null, app = app, enable = true)
        assertEquals(
            BypassRuleTrust.BUNDLED_DEDICATED,
            BypassRulePolicyResolver.resolve(
                AppRule(bundledGroup.rules.first() as RawSubscription.RawAppRule, bResolved, appInfo = null),
            ).trust,
        )
        // The local group resolves to LOCAL_IMPORT_DEDICATED.
        val lResolved = ResolvedAppGroup(localGroup, merged, subsItem(), config = null, app = app, enable = true)
        assertEquals(
            BypassRuleTrust.LOCAL_IMPORT_DEDICATED,
            BypassRulePolicyResolver.resolve(
                AppRule(localGroup.rules.first() as RawSubscription.RawAppRule, lResolved, appInfo = null),
            ).trust,
        )

        // The remap is STABLE: merging again yields the same final key (a
        // restart / bundled upgrade maps the same group to the same identity).
        val merged2 = BypassRuleStackManager.mergeBundledAndLocal(bundled, local)
        val app2 = merged2.apps.first { it.id == "com.app.a" }
        assertEquals(localGroup.key, app2.groups.first { it.name == "开屏广告-Y" }.key)
    }

    @Test
    fun local_provenance_survives_process_restart() {
        // P0-4 acceptance: local import -> persist effective subscription ->
        // clear in-memory provenance -> simulate a new process loading the
        // persisted provenance -> the imported rule still resolves to
        // LOCAL_IMPORT_DEDICATED without re-running the merge.
        BypassRuleProvenance.clear()
        val bundled = subscription(bundledJson)
        val local = subscription(localJson)
        val merged = BypassRuleStackManager.mergeBundledAndLocal(bundled, local)
        assertTrue(BypassRuleProvenance.isLocalAppGroup("com.app.a", 10))

        // The merge wrote the provenance; a fresh process reads the snapshot.
        val snapshot = BypassRuleProvenance.snapshotJson()
        assertTrue("persisted provenance must not be empty", !snapshot.isNullOrBlank())

        // New process: in-memory side-map starts empty.
        BypassRuleProvenance.clear()
        assertTrue(!BypassRuleProvenance.isLocalAppGroup("com.app.a", 10))

        // New process loads the persisted provenance file.
        BypassRuleProvenance.restoreFromJson(snapshot!!)
        assertTrue(BypassRuleProvenance.isLocalAppGroup("com.app.a", 10))

        // Resolve the EFFECTIVE subscription (loaded from disk, subs id
        // already rewritten to BYPASS_SPLASH_SUBS_ID) — the restored side-map
        // alone must recover the LOCAL_IMPORT origin.
        val app = merged.apps.first { it.id == "com.app.a" }
        val localGroup = app.groups.first { it.key == 10 }
        val resolvedGroup = ResolvedAppGroup(localGroup, merged, subsItem(), config = null, app = app, enable = true)
        val policy = BypassRulePolicyResolver.resolve(
            AppRule(localGroup.rules.first() as RawSubscription.RawAppRule, resolvedGroup, appInfo = null),
        )
        assertEquals(
            "restored provenance must recover LOCAL_IMPORT_DEDICATED across processes",
            BypassRuleTrust.LOCAL_IMPORT_DEDICATED,
            policy.trust,
        )
        // And a bundled group is still not mis-tagged.
        val keptGroup = app.groups.first { it.key == 2 }
        val keptResolved = ResolvedAppGroup(keptGroup, merged, subsItem(), config = null, app = app, enable = true)
        assertEquals(
            BypassRuleTrust.BUNDLED_DEDICATED,
            BypassRulePolicyResolver.resolve(
                AppRule(keptGroup.rules.first() as RawSubscription.RawAppRule, keptResolved, appInfo = null),
            ).trust,
        )
    }

    @Test
    fun bundled_upgrade_keeps_local_import_teach_and_origin() {
        // P0-4 acceptance (JVM level): a bundled APK upgrade re-merges the
        // persisted local import over the NEW bundled rules; the local import
        // groups, their LOCAL_IMPORT origin, and the recorded conflicts all
        // survive. (The per-group enable/disable config lives in the DB and
        // is keyed by appId+groupKey, which the remap keeps stable.)
        BypassRuleProvenance.clear()
        val bundledV1 = subscription(bundledJson)
        val local = subscription(localJson)
        val effectiveV1 = BypassRuleStackManager.mergeBundledAndLocal(bundledV1, local)
        assertTrue(effectiveV1.apps.first { it.id == "com.app.a" }.groups.any { it.name == "开屏广告-A" })

        // APK upgrade: the bundled subscription gains a new group (version
        // bump, same id). The local import file is untouched.
        val bundledV2 = subscription(
            """
            {
              "id": 100000001,
              "name": "bundled",
              "version": 2,
              "apps": [
                {"id": "com.app.a", "name": "AppA", "groups": [
                  {"key": 1, "name": "开屏广告-A", "rules": [
                    {"key": 0, "matches": ["[text=\"跳过\"]"]}
                  ]},
                  {"key": 2, "name": "开屏广告-B", "rules": [
                    {"key": 0, "matches": ["[text=\"关闭\"]"]}
                  ]},
                  {"key": 3, "name": "开屏广告-C", "rules": [
                    {"key": 0, "matches": ["[text=\"跳过\"]"]}
                  ]}
                ]}
              ]
            }
            """.trimIndent(),
        )
        val effectiveV2 = BypassRuleStackManager.mergeBundledAndLocal(bundledV2, local)

        // The local import is still present with the same identity (key 10).
        val app = effectiveV2.apps.first { it.id == "com.app.a" }
        val localGroup = app.groups.first { it.key == 10 }
        assertEquals("开屏广告-A", localGroup.name)
        val resolved = ResolvedAppGroup(localGroup, effectiveV2, subsItem(), config = null, app = app, enable = true)
        assertEquals(
            "local import origin must survive a bundled upgrade",
            BypassRuleTrust.LOCAL_IMPORT_DEDICATED,
            BypassRulePolicyResolver.resolve(
                AppRule(localGroup.rules.first() as RawSubscription.RawAppRule, resolved, appInfo = null),
            ).trust,
        )
        // The NEW bundled group also survives and keeps its bundled origin.
        val newBundled = app.groups.first { it.key == 3 }
        val newResolved = ResolvedAppGroup(newBundled, effectiveV2, subsItem(), config = null, app = app, enable = true)
        assertEquals(
            BypassRuleTrust.BUNDLED_DEDICATED,
            BypassRulePolicyResolver.resolve(
                AppRule(newBundled.rules.first() as RawSubscription.RawAppRule, newResolved, appInfo = null),
            ).trust,
        )
    }

    // ---- P0-3 (3.1/3.2/3.4): provenance rebuild + origin stamping ----

    @Test
    fun local_fake_override_is_stamped_local_import() {
        // P0-3 (3.1): a local JSON claiming "bypassOrigin":"OVERRIDE" must be
        // stamped LOCAL_IMPORT by the merge; the resolver must still refuse
        // official-override privileges.
        BypassRuleProvenance.clear()
        val bundled = subscription(bundledJson)
        val evilLocal = subscription(
            """
            {
              "id": 100000001,
              "name": "evil",
              "version": 1,
              "apps": [
                {"id": "com.app.a", "name": "AppA", "groups": [
                  {"key": 10, "name": "开屏广告-A", "rules": [
                    {"key": 0, "bypassOrigin": "OVERRIDE", "matches": ["[text=\"跳过\"]"]}
                  ]}
                ]}
              ]
            }
            """.trimIndent(),
        )
        val merged = BypassRuleStackManager.mergeBundledAndLocal(bundled, evilLocal)
        val app = merged.apps.first { it.id == "com.app.a" }
        val importedGroup = app.groups.first { it.key == 10 }
        // The rule body itself was re-stamped:
        assertEquals("LOCAL_IMPORT", (importedGroup.rules.first() as RawSubscription.RawAppRule).bypassOrigin)
        val resolved = ResolvedAppGroup(importedGroup, merged, subsItem(), config = null, app = app, enable = true)
        val policy = BypassRulePolicyResolver.resolve(
            AppRule(importedGroup.rules.first() as RawSubscription.RawAppRule, resolved, appInfo = null),
        )
        assertEquals(
            "fake OVERRIDE must resolve to LOCAL_IMPORT_DEDICATED, never BYPASS_OVERRIDE",
            BypassRuleTrust.LOCAL_IMPORT_DEDICATED,
            policy.trust,
        )
        assertEquals("minimumMode must come from LOCAL_IMPORT (AGGRESSIVE)", BypassAdStrategyMode.AGGRESSIVE, policy.minimumMode)
        assertTrue(policy.requiresStrongAdContext)
    }

    @Test
    fun import_a_then_b_rebuilds_provenance_fully() {
        // P0-3 (3.2): provenance is REBUILT on every merge. After import B,
        // import A's groups must not linger in the side-map.
        BypassRuleProvenance.clear()
        val bundled = subscription(bundledJson)
        val importA = subscription(
            """{"id": 1, "name": "A", "version": 1, "apps": [{"id": "com.app.a", "name": "AppA", "groups": [{"key": 10, "name": "开屏广告-A", "rules": [{"key": 0, "matches": ["[text=\"跳过\"]"]}]}]}]}""",
        )
        BypassRuleStackManager.mergeBundledAndLocal(bundled, importA)
        assertTrue(BypassRuleProvenance.isLocalAppGroup("com.app.a", 10))

        // Import B replaces A entirely (new key, new group name).
        val importB = subscription(
            """{"id": 1, "name": "B", "version": 1, "apps": [{"id": "com.app.a", "name": "AppA", "groups": [{"key": 20, "name": "开屏广告-B-import", "rules": [{"key": 0, "matches": ["[text=\"关闭\"]"]}]}]}]}""",
        )
        BypassRuleStackManager.mergeBundledAndLocal(bundled, importB)
        assertTrue("import B's group must be marked local", BypassRuleProvenance.isLocalAppGroup("com.app.a", 20))
        assertTrue("import A's group must be gone from the side-map", !BypassRuleProvenance.isLocalAppGroup("com.app.a", 10))
        // The persisted snapshot matches the new state:
        val snapshot = BypassRuleProvenance.snapshotJson()
        assertTrue(!snapshot.isNullOrBlank())
        assertTrue(snapshot!!.contains("\"20\""))
        assertTrue(!snapshot.contains("\"10\""))
    }

    @Test
    fun no_local_globals_keeps_bundled_globals() {
        // P0-3 (3.4): a local file without global groups must NOT null out the
        // bundled global/fallback layer — and must not claim them as local.
        BypassRuleProvenance.clear()
        val bundled = subscription(
            """{"id": 1, "name": "b", "version": 1, "globalGroups": [{"key": 1, "name": "开屏广告-全局", "rules": [{"key": 0, "matches": ["[text=\"跳过\"]"]}]}]}""",
        )
        val localWithoutGlobals = subscription(
            """{"id": 1, "name": "l", "version": 1, "apps": [{"id": "com.app.x", "name": "X", "groups": [{"key": 1, "name": "开屏广告", "rules": [{"key": 0, "matches": ["[text=\"关闭\"]"]}]}]}]}""",
        )
        val merged = BypassRuleStackManager.mergeBundledAndLocal(bundled, localWithoutGlobals)
        assertEquals(1, merged.globalGroups.size)
        assertEquals("开屏广告-全局", merged.globalGroups.first().name)
        // The preserved bundled global is NOT in the local side-map:
        assertTrue(!BypassRuleProvenance.isLocalGlobalGroup(1))
    }
}
