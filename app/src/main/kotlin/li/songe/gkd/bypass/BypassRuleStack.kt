package li.songe.gkd.bypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import li.songe.gkd.app
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.util.json
import java.io.File
/**
 * Layered rule stack:
 *
 *   Teach > Bypass official overrides > Local imports > Bundled dedicated
 *   source > Source global > Fallback
 *
 * A local import never deletes unrelated bundled coverage: groups are merged
 * per (appId + group identity), imported groups win on conflict, and every
 * other bundled group stays. Same-identity conflicts are recorded so the
 * winner is a decision, not a silent concat.
 */
object BypassRuleStackManager {
    private const val LOCAL_IMPORT_FILE = "bypass-local-import.json"

    private val lock = Mutex()
    private val localImportFile: File by lazy { File(app.filesDir, LOCAL_IMPORT_FILE) }

    data class LayerConflict(
        val appId: String,
        val groupName: String,
        val winner: String,
        /** Set when the conflict was a same-key/different-name collision that
         * got remapped instead of silently sharing one DB identity. */
        val remappedFromKey: Int? = null,
    )

    @Volatile
    var lastConflicts: List<LayerConflict> = emptyList()
        private set

    /**
     * Pure layer merge (unit-testable): returns the effective subscription
     * where [local] groups replace same-named bundled groups of the same app
     * and all other bundled groups are preserved.
     *
     * groupKey collisions (P0-4): a local group whose key equals a *kept*
     * bundled group's key but whose name differs is remapped to a stable
     * unique key derived from (appId, group name). Two different groups never
     * share one DB identity, a bundled group is never mis-tagged as local by
     * the side-map, and the user's per-group configs never cross-contaminate.
     */
    fun mergeBundledAndLocal(
        bundled: RawSubscription,
        local: RawSubscription?,
    ): RawSubscription {
        if (local == null || (local.apps.isEmpty() && local.globalGroups.isEmpty())) {
            BypassRuleProvenance.clear()
            return bundled
        }
        // P0-3 (3.1): a local import can NEVER claim an official origin. The
        // user file may say "bypassOrigin":"OVERRIDE" — the program stamps
        // every imported rule LOCAL_IMPORT before it enters the effective
        // stack (double insurance: the resolver also prefers the side-map).
        val localSanitized = stampLocalImport(local)
        // P0-3 (3.2): provenance is REBUILT from the current local layer on
        // every merge, never appended. Import A -> Import B must leave zero
        // trace of A in the side-map (and in the persisted file).
        BypassRuleProvenance.clear()
        val conflicts = mutableListOf<LayerConflict>()
        val localByApp = localSanitized.apps.associateBy { it.id }
        val apps = bundled.apps.map { bundledApp ->
            val localApp = localByApp[bundledApp.id] ?: return@map bundledApp
            val localGroupNames = localApp.groups.map { it.name }.toSet()
            // Bundled groups with the same name are replaced by the import.
            val replacedNames = localGroupNames.intersect(bundledApp.groups.map { it.name }.toSet())
            replacedNames.forEach { name ->
                conflicts += LayerConflict(bundledApp.id, name, "LOCAL_IMPORT")
            }
            val kept = bundledApp.groups.filterNot { it.name in localGroupNames }
            val keptKeys = kept.map { it.key }.toSet()
            // Same key + different name must not share one DB identity: remap
            // the imported group to a stable unique key. The side-map must be
            // recorded with the FINAL (possibly remapped) key so a kept
            // bundled group sharing the old key is never mis-tagged as local.
            val remappedLocalGroups = localApp.groups.map { group ->
                if (group.key in keptKeys) {
                    val newKey = stableRemapKey(bundledApp.id, group.name, keptKeys)
                    conflicts += LayerConflict(
                        bundledApp.id,
                        group.name,
                        "LOCAL_IMPORT_REMAPPED",
                        remappedFromKey = group.key,
                    )
                    BypassRuleProvenance.markLocalApp(bundledApp.id, newKey)
                    group.copy(key = newKey)
                } else {
                    BypassRuleProvenance.markLocalApp(bundledApp.id, group.key)
                    group
                }
            }
            bundledApp.copy(groups = kept + remappedLocalGroups)
        }.toMutableList()
        // Apps only present in the import are added whole (their keys are
        // already recorded above via the local-marking loop below).
        localByApp.forEach { (packageName, localApp) ->
            if (apps.none { it.id == packageName }) {
                localApp.groups.forEach { group -> BypassRuleProvenance.markLocalApp(packageName, group.key) }
                apps += localApp
            }
        }
        lastConflicts = conflicts
        val globals = if (localSanitized.globalGroups.isEmpty()) {
            // P0-3 (3.4): the local layer only carries the local file's own
            // globals; bundled global/fallback is preserved by the merge.
            bundled.globalGroups
        } else {
            val localGlobalNames = localSanitized.globalGroups.map { it.name }.toSet()
            val keptGlobals = bundled.globalGroups.filterNot { it.name in localGlobalNames }
            val keptGlobalKeys = keptGlobals.map { it.key }.toSet()
            val remappedLocalGlobals = localSanitized.globalGroups.map { group ->
                if (group.key in keptGlobalKeys) {
                    val newKey = stableRemapKey("global", group.name, keptGlobalKeys)
                    conflicts += LayerConflict(
                        "global",
                        group.name,
                        "LOCAL_IMPORT_REMAPPED",
                        remappedFromKey = group.key,
                    )
                    BypassRuleProvenance.markLocalGlobal(newKey)
                    group.copy(key = newKey)
                } else {
                    BypassRuleProvenance.markLocalGlobal(group.key)
                    group
                }
            }
            keptGlobals + remappedLocalGlobals
        }
        return bundled.copy(apps = apps, globalGroups = globals)
    }

    /**
     * P0-3 (3.1): force every imported rule's structured origin to
     * LOCAL_IMPORT. Whatever the user file claims (OVERRIDE / BUNDLED /
     * anything), the program's local layer is always LOCAL_IMPORT — a local
     * import must never obtain official-override privileges.
     */
    private fun stampLocalImport(local: RawSubscription): RawSubscription = local.copy(
        apps = local.apps.map { app ->
            app.copy(groups = app.groups.map { group ->
                group.copy(rules = group.rules.map { rule ->
                    rule.copy(bypassOrigin = "LOCAL_IMPORT")
                })
            })
        },
        globalGroups = local.globalGroups.map { group ->
            group.copy(rules = group.rules.map { rule ->
                rule.copy(bypassOrigin = "LOCAL_IMPORT")
            })
        },
    )

    /**
     * Stable unique remap key for a colliding imported group: derived from
     * (scope, group name) so a restart / bundled upgrade maps the same group
     * to the same key; bumped past any key already in use in this merge.
     */
    private fun stableRemapKey(scope: String, groupName: String, usedKeys: Set<Int>): Int {
        val seed = (scope + "|" + groupName).hashCode() and 0x3fffffff
        var candidate = REMAP_BASE + seed
        while (candidate in usedKeys || candidate == seed) {
            candidate = (candidate + 1) and 0x7fffffff
            if (candidate < REMAP_BASE) candidate = REMAP_BASE
        }
        return candidate
    }

    private const val REMAP_BASE = 0x40000000

    suspend fun readLocalImport(): RawSubscription? = withContext(Dispatchers.IO) {
        if (!localImportFile.exists()) return@withContext null
        runCatching {
            RawSubscription.parse(localImportFile.readText(), json5 = false)
        }.getOrNull()
    }

    suspend fun saveLocalImport(subscription: RawSubscription): Unit = withContext(Dispatchers.IO) {
        // Atomic write: temp file + rename.
        val tmp = File(localImportFile.parentFile, "$LOCAL_IMPORT_FILE.tmp")
        tmp.writeText(json.encodeToString(RawSubscription.serializer(), subscription))
        if (!tmp.renameTo(localImportFile)) {
            localImportFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    suspend fun clearLocalImport() {
        BypassRuleProvenance.clear()
        lock.withLock {
            withContext(Dispatchers.IO) {
                if (localImportFile.exists()) localImportFile.delete()
            }
        }
    }

    /** Whether a local import layer currently exists. */
    suspend fun hasLocalImport(): Boolean = withContext(Dispatchers.IO) {
        localImportFile.exists()
    }
}
