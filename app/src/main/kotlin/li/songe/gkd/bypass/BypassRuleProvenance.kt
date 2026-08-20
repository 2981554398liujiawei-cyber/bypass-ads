package li.songe.gkd.bypass

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import li.songe.gkd.util.json
import java.io.File
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
 *
 * Persistence (P0-4): the side-map is mirrored to a small JSON file so it
 * survives process restarts. [restore] is invoked lazily from the first
 * resolution (never from UI initialization), so the engine's rule
 * resolution always sees the correct origin even when the UI has not been
 * opened. File access is best-effort: JVM unit tests (no Android app
 * context) simply skip persistence.
 */
object BypassRuleProvenance {

    private const val FILE_NAME = "bypass_provenance.json"

    private val localAppGroups = ConcurrentHashMap<String, Unit>()
    private val localGlobalGroups = ConcurrentHashMap<String, Unit>()
    private val restoreLock = Any()
    @Volatile
    private var restored = false

    private fun persistedFile(): File? =
        runCatching { File(li.songe.gkd.app.filesDir, FILE_NAME) }.getOrNull()

    /** Load the persisted side-map exactly once, before any resolution. */
    fun restore() {
        if (restored) return
        synchronized(restoreLock) {
            if (restored) return
            restored = true
            val file = persistedFile() ?: return
            val text = runCatching { file.readText() }.getOrNull() ?: return
            parseAndLoad(text)
        }
    }

    /**
     * Process-restart simulation hook (P0-4): parse a persisted snapshot into
     * a FRESH side-map, exactly like a new process would after loading the
     * provenance file. Clears any current entries first.
     */
    internal fun restoreFromJson(text: String) {
        synchronized(restoreLock) {
            restored = true
            localAppGroups.clear()
            localGlobalGroups.clear()
            parseAndLoad(text)
        }
    }

    /** Serialize the current side-map (persist/restart round-trip hook). */
    internal fun snapshotJson(): String? {
        restore()
        return encode()
    }

    private fun parseAndLoad(text: String) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        (root["localApps"] as? JsonArray ?: JsonArray(emptyList())).forEach { el ->
            val obj = el.jsonObject
            val appId = obj["app"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val groupKey = obj["key"]?.jsonPrimitive?.intOrNull ?: return@forEach
            localAppGroups[key(appId, groupKey)] = Unit
        }
        (root["localGlobals"] as? JsonArray ?: JsonArray(emptyList())).forEach { el ->
            val groupKey = el.jsonPrimitive.intOrNull ?: return@forEach
            localGlobalGroups[key("global", groupKey)] = Unit
        }
    }

    internal fun ensureRestored() = restore()

    fun isLocalAppGroup(appId: String?, groupKey: Int?): Boolean {
        restore()
        return appId != null && groupKey != null && localAppGroups.containsKey(key(appId, groupKey))
    }

    fun isLocalGlobalGroup(groupKey: Int?): Boolean {
        restore()
        return groupKey != null && localGlobalGroups.containsKey(key("global", groupKey))
    }

    internal fun markLocalApp(appId: String, groupKey: Int) {
        restore()
        localAppGroups[key(appId, groupKey)] = Unit
        persist()
    }

    internal fun markLocalGlobal(groupKey: Int) {
        restore()
        localGlobalGroups[key("global", groupKey)] = Unit
        persist()
    }

    /** Wipe the side-map (restore-bundled / no local layer). */
    fun clear() {
        localAppGroups.clear()
        localGlobalGroups.clear()
        restored = true
        persistedFile()?.let { file -> runCatching { file.delete() } }
    }

    private fun persist() {
        val file = persistedFile() ?: return
        val text = encode() ?: return
        runCatching { file.writeText(text) }
    }

    private fun encode(): String? {
        val appEntries = buildJsonArray {
            localAppGroups.keys.sorted().forEach { k ->
                val sep = k.indexOf('|')
                if (sep > 0) {
                    add(
                        buildJsonObject {
                            put("app", JsonPrimitive(k.substring(0, sep)))
                            put("key", JsonPrimitive(k.substring(sep + 1)))
                        }
                    )
                }
            }
        }
        val globalEntries = buildJsonArray {
            localGlobalGroups.keys.sorted().forEach { k ->
                val sep = k.indexOf('|')
                if (sep > 0) {
                    k.substring(sep + 1).toIntOrNull()?.let { add(JsonPrimitive(it)) }
                }
            }
        }
        return runCatching {
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("localApps", appEntries)
                    put("localGlobals", globalEntries)
                },
            )
        }.getOrNull()
    }

    private fun key(scope: String, groupKey: Int) = "$scope|$groupKey"
}
