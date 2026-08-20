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
    )

    @Volatile
    var lastConflicts: List<LayerConflict> = emptyList()
        private set

    /**
     * Pure layer merge (unit-testable): returns the effective subscription
     * where [local] groups replace same-named bundled groups of the same app
     * and all other bundled groups are preserved.
     */
    fun mergeBundledAndLocal(
        bundled: RawSubscription,
        local: RawSubscription?,
    ): RawSubscription {
        if (local == null || (local.apps.isEmpty() && local.globalGroups.isEmpty())) {
            BypassRuleProvenance.clear()
            return bundled
        }
        // Record the structured origin of every imported group BEFORE the
        // merge rewrites identities: the resolver needs the side-map to tell
        // imported rules from bundled ones once they share BYPASS_SPLASH_SUBS_ID.
        local.apps.forEach { localApp ->
            localApp.groups.forEach { group -> BypassRuleProvenance.markLocalApp(localApp.id, group.key) }
        }
        local.globalGroups.forEach { group -> BypassRuleProvenance.markLocalGlobal(group.key) }
        val conflicts = mutableListOf<LayerConflict>()
        val localByApp = local.apps.associateBy { it.id }
        val apps = bundled.apps.map { bundledApp ->
            val localApp = localByApp[bundledApp.id] ?: return@map bundledApp
            val localGroupNames = localApp.groups.map { it.name }.toSet()
            // Bundled groups with the same name are replaced by the import.
            localGroupNames.forEach { name ->
                conflicts += LayerConflict(bundledApp.id, name, "LOCAL_IMPORT")
            }
            bundledApp.copy(
                groups = bundledApp.groups.filterNot { it.name in localGroupNames } + localApp.groups,
            )
        }.toMutableList()
        // Apps only present in the import are added whole.
        localByApp.forEach { (packageName, localApp) ->
            if (apps.none { it.id == packageName }) apps += localApp
        }
        lastConflicts = conflicts
        val globals = if (local.globalGroups.isEmpty()) {
            bundled.globalGroups
        } else {
            val localGlobalNames = local.globalGroups.map { it.name }.toSet()
            bundled.globalGroups.filterNot { it.name in localGlobalNames } + local.globalGroups
        }
        return bundled.copy(apps = apps, globalGroups = globals)
    }

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
