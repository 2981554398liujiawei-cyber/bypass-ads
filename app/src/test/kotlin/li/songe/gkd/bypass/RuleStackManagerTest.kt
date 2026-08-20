package li.songe.gkd.bypass

import li.songe.gkd.data.RawSubscription
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
}
