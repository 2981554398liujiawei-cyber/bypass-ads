package li.songe.gkd.bypass

import java.util.concurrent.ConcurrentHashMap

/**
 * Stable origin side-map for the layered rule stack.
 *
 * The local-import merge rewrites imported rules to BYPASS_SPLASH_SUBS_ID, so
 * `subsId != BYPASS_SPLASH_SUBS_ID` can no longer separate bundled from
 * imported rules. This map is the structured provenance source the
 * [BypassRulePolicyResolver] consults, keyed by the same identity the
 * resolver already uses (appId + groupKey for app groups, groupKey for
 * global groups). Origins are never guessed from rule names.
 *
 * The map is populated ONLY by the merge step and cleared when the local
 * layer is removed, so it can never drift from the effective subscription.
 */
object BypassRuleProvenance {

    private val localAppGroups = ConcurrentHashMap<String, Unit>()
    private val localGlobalGroups = ConcurrentHashMap<String, Unit>()

    fun isLocalAppGroup(appId: String?, groupKey: Int?): Boolean =
        appId != null && groupKey != null && localAppGroups.containsKey(key(appId, groupKey))

    fun isLocalGlobalGroup(groupKey: Int?): Boolean =
        groupKey != null && localGlobalGroups.containsKey(key("global", groupKey))

    internal fun markLocalApp(appId: String, groupKey: Int) {
        localAppGroups[key(appId, groupKey)] = Unit
    }

    internal fun markLocalGlobal(groupKey: Int) {
        localGlobalGroups[key("global", groupKey)] = Unit
    }

    /** Wipe the side-map (restore-bundled / no local layer). */
    fun clear() {
        localAppGroups.clear()
        localGlobalGroups.clear()
    }

    private fun key(scope: String, groupKey: Int) = "$scope|$groupKey"
}
