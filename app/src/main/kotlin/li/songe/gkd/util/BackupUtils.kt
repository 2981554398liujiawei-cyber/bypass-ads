package li.songe.gkd.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import li.songe.gkd.bypass.GkdBypassEngine
import li.songe.gkd.bypass.BypassRuleMetadata
import li.songe.gkd.bypass.BypassRuleSourceType
import li.songe.gkd.data.AppConfig
import li.songe.gkd.data.CategoryConfig
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.SubsConfig
import li.songe.gkd.data.SubsItem
import li.songe.gkd.db.DbSet
import li.songe.gkd.store.a11yScopeAppListFlow
import li.songe.gkd.store.actionCountFlow
import li.songe.gkd.store.blockA11yAppListFlow
import li.songe.gkd.store.blockMatchAppListFlow
import li.songe.gkd.store.storeFlow
import java.io.File

@Serializable
private data class DbData(
    val subsItems: List<SubsItem>?,
    val subsConfigs: List<SubsConfig>?,
    val categoryConfigs: List<CategoryConfig>?,
    val appConfigs: List<AppConfig>?,
)

/**
 * V1 backup/restore contract (UI promise): the backup contains the product
 * settings (storeFlow), Bypass app/group/category enable configs, the rule
 * subscription, the LOCAL IMPORT rules, the TEACH rules + verification state
 * and the local source metadata.
 *
 * NOT backed up: accessibility trees, page content, transient diagnostics,
 * armed retest windows, and the provenance side-map — provenance is
 * REBUILT from the local layer after restore.
 *
 * Restore of Bypass's own configuration is AUTHORITATIVE (not insertOrIgnore):
 * the BYPASS_SPLASH_SUBS_ID rows are replaced with the backup state, then the
 * effective stack is rebuilt from the clean bundled base + restored local +
 * teach. Non-Bypass GKD data is left untouched.
 */
object BackupUtils {
    private const val BYPASS_STATE_ENTRY = "bypass/bypass_state.json"

    private val backupStoreFlowList
        get() = listOf(
            storeFlow,
            actionCountFlow,
            blockMatchAppListFlow,
            blockA11yAppListFlow,
            a11yScopeAppListFlow,
        )

    @Serializable
    private data class BypassState(
        /** bypass_ad_categories SharedPreferences (category switches). */
        val categories: Map<String, Boolean> = emptyMap(),
        /** bypass_teach_verification SharedPreferences (teach verification state). */
        val teachVerification: Map<String, String> = emptyMap(),
        /** Local source metadata (sourceType / sourceFileName / bundleVersion / sha256 / installedAt). */
        val metadataSourceType: String = BypassRuleSourceType.BUNDLED.name,
        val metadataSourceFileName: String? = null,
        val metadataBundleVersion: Int? = null,
        val metadataInstalledAt: Long = 0L,
        val metadataSha256: String = "",
        /** Raw text of bypass-local-import.json (the local layer itself). */
        val localImportJson: String? = null,
        /** Raw text of bypass-teach-overrides.json (the teach layer itself). */
        val teachJson: String? = null,
    )

    suspend fun exportBackUpData(): File {
        val tempDir = createGkdTempDir()
        tempDir.resolve("store").run {
            mkdir()
            backupStoreFlowList.forEach { storeFlow ->
                resolve(storeFlow.filename).writeText(storeFlow.encodeSelf())
            }
        }
        tempDir.resolve("db.json").writeText(
            json.encodeToString(
                DbData(
                    subsItems = DbSet.subsItemDao.queryAll(),
                    subsConfigs = DbSet.subsConfigDao.queryAll(),
                    categoryConfigs = DbSet.categoryConfigDao.queryAll(),
                    appConfigs = DbSet.appConfigDao.queryAll(),
                )
            )
        )
        tempDir.resolve("subscription").run {
            mkdir()
            subsMapFlow.value.values.forEach { subs ->
                resolve("${subs.id}.json").writeText(json.encodeToString(subs))
            }
        }
        // Bypass self-owned state (P0/P1-4): local import + teach layers,
        // category prefs, teach verification prefs, local source metadata.
        val bypassState = buildBypassState()
        tempDir.resolve("bypass").run {
            mkdir()
            resolve("bypass_state.json").writeText(json.encodeToString(bypassState))
        }
        val file = sharedDir.resolve("gkd-backup-${System.currentTimeMillis()}.zip")
        ZipUtils.zipFiles(tempDir.listFiles()!!.filterNotNull(), file)
        tempDir.deleteRecursively()
        return file
    }

    private suspend fun buildBypassState(): BypassState {
        val metadata = GkdBypassEngine.ruleMetadata.value
        val categoryPrefs = li.songe.gkd.app.getSharedPreferences("bypass_ad_categories", Context.MODE_PRIVATE)
        val teachVerificationPrefs = li.songe.gkd.app.getSharedPreferences("bypass_teach_verification", Context.MODE_PRIVATE)
        val categoryMap = BypassAdCategoryPrefs.allNames().associateWith { categoryPrefs.getBoolean(it, false) }
        val verificationMap = teachVerificationPrefs.all.entries.associate { it.key to it.value.toString() }
        return BypassState(
            categories = categoryMap,
            teachVerification = verificationMap,
            metadataSourceType = metadata.sourceType.name,
            metadataSourceFileName = metadata.sourceFileName,
            metadataBundleVersion = metadata.bundleVersion,
            metadataInstalledAt = metadata.installedAt,
            metadataSha256 = metadata.sha256,
            localImportJson = filesDirFile("bypass-local-import.json")?.takeIf { it.exists() }?.readText(),
            teachJson = filesDirFile("bypass-teach-overrides.json")?.takeIf { it.exists() }?.readText(),
        )
    }

    private fun filesDirFile(name: String): File? =
        runCatching { File(li.songe.gkd.app.filesDir, name) }.getOrNull()

    suspend fun importBackUpData(uri: Uri) {
        toast("导入备份中...")
        val tempDir = createGkdTempDir()
        val zipFile = tempDir.resolve("file.zip").apply {
            writeBytes(UriUtils.uri2Bytes(uri))
        }
        val unzipDir = tempDir.resolve("unzip")
        try {
            ZipUtils.unzipFile(zipFile, unzipDir)
            zipFile.delete()
        } catch (e: Exception) {
            LogUtils.d("importBackUpData.unzipFile", e)
            toast("解压失败，非法备份文件")
            tempDir.deleteRecursively()
            return
        }
        backupStoreFlowList.forEach { storeFlow ->
            val file = unzipDir.resolve("store/${storeFlow.filename}")
            if (file.exists() && file.isFile) {
                try {
                    storeFlow.updateByDecode(file.readText())
                } catch (e: Exception) {
                    LogUtils.d("importBackUpData.updateByDecode", storeFlow.filename, e)
                }
            }
        }
        val dbFile = unzipDir.resolve("db.json")
        if (dbFile.exists() && dbFile.isFile) {
            val dbData = withContext(Dispatchers.Default) {
                json.decodeFromString<DbData>(dbFile.readText())
            }
            if (!dbData.subsItems.isNullOrEmpty()) {
                DbSet.subsItemDao.insertOrIgnore(*dbData.subsItems.toTypedArray())
            }
            // P0/P1-4: Bypass's own config restore is AUTHORITATIVE — replace
            // every BYPASS_SPLASH_SUBS_ID row with the backup state instead of
            // insertOrIgnore, so an older stale row can never survive a newer
            // backup. Other GKD subscriptions keep the lenient insertOrIgnore.
            restoreBypassConfigs(dbData)
        }
        val subsDir = unzipDir.resolve("subscription")
        if (subsDir.exists() && subsDir.isDirectory) {
            (subsDir.listFiles {
                it.isFile && it.name.endsWith(".json")
            } ?: emptyArray()).filterNotNull().forEach { file ->
                try {
                    val subs = withContext(Dispatchers.Default) {
                        json.decodeFromString<RawSubscription>(file.readText())
                    }
                    // Bypass has one authoritative restore path below:
                    // clean APK bundled base + restored Local + Teach. Do
                    // not enqueue a generic async update for it, or that
                    // update can overwrite the rebuilt stack afterwards.
                    if (subs.id != BYPASS_SPLASH_SUBS_ID) {
                        updateSubscription(subs)
                    }
                } catch (e: Exception) {
                    LogUtils.d("importBackUpData.saveSubs", file.name, e)
                }
            }
        }
        // P0/P1-4: restore Bypass self-owned state (local/teach files, category
        // and verification prefs, metadata) and REBUILD the effective stack
        // from the clean bundled base + restored local + teach. Provenance is
        // rebuilt by the merge — it is intentionally not backed up.
        val bypassStateFile = unzipDir.resolve(BYPASS_STATE_ENTRY)
        if (bypassStateFile.exists() && bypassStateFile.isFile) {
            val state = runCatching { json.decodeFromString<BypassState>(bypassStateFile.readText()) }
                .getOrNull()
            state?.let { restoreBypassState(it) }
        }
        val rebuild = runCatching { GkdBypassEngine.rebuildEffectiveStack() }.getOrNull()
        if (rebuild != null && !rebuild.accepted) {
            LogUtils.d("importBackUpData.rebuildEffectiveStack", rebuild.message)
        }
        toast("导入成功")
        tempDir.deleteRecursively()
        delay(1000)
        checkSubsUpdate(false)
    }

    /** Replace (delete + insert) the Bypass subscription's own config rows. */
    private suspend fun restoreBypassConfigs(dbData: DbData) {
        val bypassSubsConfigs = dbData.subsConfigs.orEmpty().filter { it.subsId == BYPASS_SPLASH_SUBS_ID }
        val bypassAppConfigs = dbData.appConfigs.orEmpty().filter { it.subsId == BYPASS_SPLASH_SUBS_ID }
        val bypassCategoryConfigs = dbData.categoryConfigs.orEmpty().filter { it.subsId == BYPASS_SPLASH_SUBS_ID }
        DbSet.subsConfigDao.deleteBySubsId(BYPASS_SPLASH_SUBS_ID)
        if (bypassSubsConfigs.isNotEmpty()) {
            DbSet.subsConfigDao.insert(*bypassSubsConfigs.toTypedArray())
        }
        DbSet.appConfigDao.deleteBySubsId(BYPASS_SPLASH_SUBS_ID)
        if (bypassAppConfigs.isNotEmpty()) {
            DbSet.appConfigDao.insert(*bypassAppConfigs.toTypedArray())
        }
        DbSet.categoryConfigDao.deleteBySubsId(BYPASS_SPLASH_SUBS_ID)
        if (bypassCategoryConfigs.isNotEmpty()) {
            DbSet.categoryConfigDao.insert(*bypassCategoryConfigs.toTypedArray())
        }
        // Non-Bypass rows restore leniently (untouched GKD data).
        dbData.subsConfigs.orEmpty().filter { it.subsId != BYPASS_SPLASH_SUBS_ID }
            .takeIf { it.isNotEmpty() }?.let { DbSet.subsConfigDao.insertOrIgnore(*it.toTypedArray()) }
        dbData.appConfigs.orEmpty().filter { it.subsId != BYPASS_SPLASH_SUBS_ID }
            .takeIf { it.isNotEmpty() }?.let { DbSet.appConfigDao.insertOrIgnore(*it.toTypedArray()) }
        dbData.categoryConfigs.orEmpty().filter { it.subsId != BYPASS_SPLASH_SUBS_ID }
            .takeIf { it.isNotEmpty() }?.let { DbSet.categoryConfigDao.insertOrIgnore(*it.toTypedArray()) }
    }

    /** Restore Bypass self-owned files/prefs (authoritative). */
    private suspend fun restoreBypassState(state: BypassState) {
        // Local import layer: replace the file with the backup's exact bytes.
        if (state.localImportJson != null) {
            filesDirFile("bypass-local-import.json")?.writeText(state.localImportJson)
        } else {
            runCatching { filesDirFile("bypass-local-import.json")?.delete() }
        }
        // Teach layer: replace the file with the backup's exact bytes.
        if (state.teachJson != null) {
            filesDirFile("bypass-teach-overrides.json")?.writeText(state.teachJson)
        } else {
            runCatching { filesDirFile("bypass-teach-overrides.json")?.delete() }
        }
        // Category switches (bypass_ad_categories) — replace with backup state.
        val categoryPrefs = li.songe.gkd.app.getSharedPreferences("bypass_ad_categories", Context.MODE_PRIVATE)
        categoryPrefs.edit().clear().apply {
            state.categories.forEach { (name, enabled) -> putBoolean(name, enabled) }
            apply()
        }
        // Teach verification state (bypass_teach_verification) — replace.
        val verificationPrefs = li.songe.gkd.app.getSharedPreferences("bypass_teach_verification", Context.MODE_PRIVATE)
        verificationPrefs.edit().clear().apply {
            state.teachVerification.forEach { (key, value) -> putString(key, value) }
            apply()
        }
        // Local source metadata — restore the truthful record (type/file/time).
        val sourceType = runCatching { BypassRuleSourceType.valueOf(state.metadataSourceType) }
            .getOrDefault(BypassRuleSourceType.BUNDLED)
        val previous = GkdBypassEngine.ruleMetadata.value
        val restoredMetadata = BypassRuleMetadata(
            sourceType = sourceType,
            bundleVersion = state.metadataBundleVersion ?: previous.bundleVersion,
            installedAt = state.metadataInstalledAt.takeIf { it > 0L } ?: previous.installedAt,
            appCount = previous.appCount,
            groupCount = previous.groupCount,
            ruleCount = previous.ruleCount,
            sha256 = state.metadataSha256.ifBlank { previous.sha256 },
            sourceFileName = state.metadataSourceFileName ?: previous.sourceFileName,
        )
        GkdBypassEngine.restoreMetadata(restoredMetadata)
        // Armed retest windows are deliberately NOT restored.
        li.songe.gkd.app.getSharedPreferences("bypass_teach_retest", Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/** Small helper: the names of the category prefs keys (BypassAdCategory names). */
private object BypassAdCategoryPrefs {
    fun allNames(): List<String> = listOf("SPLASH", "IN_APP_FULLSCREEN", "MARKETING_POPUP", "OTHER_CLOSABLE")
}
