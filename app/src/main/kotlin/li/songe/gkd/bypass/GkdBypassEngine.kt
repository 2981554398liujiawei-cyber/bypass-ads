package li.songe.gkd.bypass

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import li.songe.gkd.BYPASS_SPLASH_ASSETS_LOCAL_NAME
import li.songe.gkd.BYPASS_SPLASH_ASSETS_NAME
import li.songe.gkd.app
import li.songe.gkd.appScope
import li.songe.gkd.data.AppConfig
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.data.SubsConfig
import li.songe.gkd.db.DbSet
import li.songe.gkd.service.fixRestartAutomatorService
import li.songe.gkd.service.A11yService
import li.songe.gkd.store.storeFlow
import li.songe.gkd.util.appInfoMapFlow
import li.songe.gkd.util.launchTry
import li.songe.gkd.util.mapState
import li.songe.gkd.util.subsMapFlow
import li.songe.gkd.util.updateSubscription
import java.security.MessageDigest

/**
 * Key of the generic splash fallback global group; must stay in sync with
 * GENERIC_FALLBACK_GROUP in tools/build_splash_bundle.py.
 */
const val GENERIC_FALLBACK_GROUP_KEY = 9000

/**
 * GKD-backed [BypassEngine] adapter.
 *
 * Product UI stays on [BypassEngine]; this object is the only place that
 * touches GKD internals for product-facing state.
 */
object GkdBypassEngine : BypassEngine {

    private const val METADATA_PREFS = "bypass_rule_metadata"
    private val metadataPrefs by lazy { app.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE) }
    private val metadataFlow = MutableStateFlow(readMetadata())
    private val permissionStateFlow = MutableStateFlow(readPermissionState())

    init {
        // Existing installs from before R3 receive a truthful bundled record on
        // first launch. The timestamp is the installed APK's update time, not
        // an invented rule-import time.
        appScope.launchTry(Dispatchers.IO) {
            val bundle = subsMapFlow.map { it[BYPASS_SPLASH_SUBS_ID] }.filterNotNull().first()
            if (metadataFlow.value.bundleVersion == null) {
                saveMetadata(metadataFor(bundle, BypassRuleSourceType.BUNDLED, null, app.packageManager
                    .getPackageInfo(app.packageName, 0).lastUpdateTime))
            }
        }
    }

    /** Whether the accessibility service component is currently enabled in
     * system settings (used to distinguish "正在恢复" from "需要授权"). */
    private fun checkA11yAuthorized(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            li.songe.gkd.app.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it == A11yService.a11yCn.flattenToString() }
    }

    override val serviceState: StateFlow<BypassServiceState> =
        A11yService.isRunning.map { running ->
            when {
                running -> BypassServiceState(BypassServiceStatus.NORMAL)
                !checkA11yAuthorized() -> BypassServiceState(BypassServiceStatus.NEED_AUTHORIZATION)
                else -> BypassServiceState(BypassServiceStatus.RECOVERING)
            }
        }.stateIn(appScope, SharingStarted.Eagerly, BypassServiceState(BypassServiceStatus.OFF))

    override val masterEnabled: StateFlow<Boolean> =
        storeFlow.mapState(appScope) { it.enableMatch }

    override val stats: StateFlow<BypassRuleStats> =
        subsMapFlow.map { subscriptions ->
            // Coverage is a property of the bundled Bypass rule stack, not of
            // the apps currently installed on this particular phone.  The
            // latter made a 170-app bundle misleadingly show "30 个应用".
            val bundle = subscriptions[BYPASS_SPLASH_SUBS_ID]
            BypassRuleStats(
                appCount = bundle?.apps?.size ?: 0,
                // This is deliberately the dedicated-app group count. The
                // single conservative generic fallback is reported separately
                // as a capability, not disguised as a dedicated rule group.
                groupCount = bundle?.apps?.sumOf { it.groups.size } ?: 0,
                ruleCount = bundle?.apps?.sumOf { app ->
                    app.groups.sumOf { group -> group.rules.size }
                }?.plus(bundle.globalGroups.sumOf { group -> group.rules.size }) ?: 0,
            )
        }.stateIn(appScope, SharingStarted.Eagerly, BypassRuleStats())

    override val ruleMetadata: StateFlow<BypassRuleMetadata> = metadataFlow

    override val permissionState: StateFlow<BypassPermissionState> = permissionStateFlow

    override val recentActions: StateFlow<List<BypassActionRecord>> =
        combine(
            DbSet.actionLogDao.query(),
            appInfoMapFlow,
            subsMapFlow,
        ) { records, appMap, subsMap ->
            records.asSequence()
                // Do not show historical entries that might have been left by
                // an earlier GKD install: this is Bypass Ads product history.
                .filter { it.subsId == BYPASS_SPLASH_SUBS_ID }
                .take(30)
                .map { r ->
                    val groupName = if (r.groupType == SubsConfig.AppGroupType) {
                        subsMap[r.subsId]?.apps?.find { it.id == r.appId }
                            ?.groups?.find { it.key == r.groupKey }?.name
                    } else {
                        subsMap[r.subsId]?.globalGroups
                            ?.find { it.key == r.groupKey }?.name
                    }
                BypassActionRecord(
                    appId = r.appId,
                    appName = appMap[r.appId]?.name,
                    groupName = groupName,
                    time = r.ctime,
                )
            }.toList()
        }.stateIn(appScope, SharingStarted.Eagerly, emptyList())

    override val latestAction: StateFlow<BypassActionRecord?> =
        recentActions.map { it.firstOrNull() }
            .stateIn(appScope, SharingStarted.Eagerly, null)

    override val skipCount: StateFlow<Int> =
        DbSet.actionLogDao.query().map { actions -> actions.count { it.subsId == BYPASS_SPLASH_SUBS_ID } }
            .stateIn(appScope, SharingStarted.Eagerly, 0)

    override val genericFallbackEnabled: StateFlow<Boolean> =
        storeFlow.mapState(appScope) { it.enableGenericFallback }

    override val actionToastEnabled: StateFlow<Boolean> =
        storeFlow.mapState(appScope) { it.toastWhenClick }

    override fun setMasterEnabled(enabled: Boolean) {
        storeFlow.value = storeFlow.value.copy(enableMatch = enabled)
    }

    override fun setGenericFallbackEnabled(enabled: Boolean) {
        storeFlow.value = storeFlow.value.copy(enableGenericFallback = enabled)
        // Gate the actual fallback global group through SubsConfig so the UI
        // switch really controls whether the rule executes.
        appScope.launchTry(Dispatchers.IO) {
            val existing = DbSet.subsConfigDao.queryAll().find {
                it.subsId == BYPASS_SPLASH_SUBS_ID &&
                    it.groupKey == GENERIC_FALLBACK_GROUP_KEY &&
                    it.type == SubsConfig.GlobalGroupType
            }
            DbSet.subsConfigDao.insert(
                existing?.copy(enable = enabled)
                    ?: SubsConfig(
                        type = SubsConfig.GlobalGroupType,
                        enable = enabled,
                        subsId = BYPASS_SPLASH_SUBS_ID,
                        groupKey = GENERIC_FALLBACK_GROUP_KEY,
                    )
            )
        }
    }

    override fun setActionToastEnabled(enabled: Boolean) {
        storeFlow.value = storeFlow.value.copy(toastWhenClick = enabled)
    }

    override fun refreshPermissionState() {
        permissionStateFlow.value = readPermissionState()
    }

    override fun requestServiceRecovery() {
        appScope.launchTry(Dispatchers.IO) { fixRestartAutomatorService() }
    }

    override suspend fun importLocalRules(source: String, sourceFileName: String?): BypassImportResult {
        val parsed = runCatching { RawSubscription.parse(source) }.getOrElse {
            return BypassImportResult(false, "无法解析规则文件")
        }
        val splashApps = parsed.apps.mapNotNull { app ->
            val groups = app.groups.filter { it.name == "开屏广告" || it.name.startsWith("开屏广告-") }
            app.takeIf { groups.isNotEmpty() }?.copy(groups = groups)
        }
        if (splashApps.isEmpty()) return BypassImportResult(false, "文件中没有可导入的开屏规则")
        val current = subsMapFlow.value[BYPASS_SPLASH_SUBS_ID]
        val imported = parsed.copy(
            id = BYPASS_SPLASH_SUBS_ID,
            name = "Bypass Ads 开屏规则",
            // Preserve the package's actual declared version. A local import
            // must never manufacture a version from the previously active set.
            version = parsed.version,
            apps = splashApps,
            // Never accept external global rules in this product: retain only
            // our known conservative fallback from the active Bypass bundle.
            globalGroups = current?.globalGroups ?: emptyList(),
        )
        updateSubscription(imported)
        saveMetadata(metadataFor(imported, BypassRuleSourceType.LOCAL_IMPORT, sourceFileName))
        return BypassImportResult(true, "已导入 ${splashApps.size} 个应用的开屏规则")
    }

    override suspend fun restoreBundledRules(): BypassImportResult {
        val raw = runCatching {
            app.assets.open(BYPASS_SPLASH_ASSETS_LOCAL_NAME).bufferedReader().use { it.readText() }
        }.recoverCatching {
            app.assets.open(BYPASS_SPLASH_ASSETS_NAME).bufferedReader().use { it.readText() }
        }.getOrElse { return BypassImportResult(false, "内置规则包不可用") }
        val restored = runCatching { RawSubscription.parse(raw, json5 = false) }.getOrElse {
            return BypassImportResult(false, "内置规则包无效")
        }
        val bundled = restored.copy(id = BYPASS_SPLASH_SUBS_ID)
        updateSubscription(bundled)
        saveMetadata(metadataFor(bundled, BypassRuleSourceType.BUNDLED, null))
        return BypassImportResult(true, "已恢复内置开屏规则")
    }

    override suspend fun clearRecentActions() {
        DbSet.actionLogDao.deleteBySubsId(BYPASS_SPLASH_SUBS_ID)
    }

    override suspend fun getProtectedApps(): List<BypassAppInfo> {
        val subs = li.songe.gkd.util.subsMapFlow.value[BYPASS_SPLASH_SUBS_ID] ?: return emptyList()
        val appConfigs = DbSet.appConfigDao.queryAll()
        val appMap = appInfoMapFlow.value
        return subs.apps.map { app ->
            val config = appConfigs.find { c -> c.subsId == BYPASS_SPLASH_SUBS_ID && c.appId == app.id }
            BypassAppInfo(
                packageName = app.id,
                appName = appMap[app.id]?.name ?: app.id,
                enabled = config?.enable ?: true,
                groupCount = app.groups.size,
            )
        }
    }

    override suspend fun getAppEnabled(packageName: String): Boolean {
        val config = DbSet.appConfigDao.queryAll()
            .find { c -> c.subsId == BYPASS_SPLASH_SUBS_ID && c.appId == packageName }
        return config?.enable ?: true
    }

    override suspend fun setAppEnabled(packageName: String, enabled: Boolean) {
        val existing = DbSet.appConfigDao.queryAll()
            .find { c -> c.subsId == BYPASS_SPLASH_SUBS_ID && c.appId == packageName }
        DbSet.appConfigDao.insert(
            existing?.copy(enable = enabled)
                ?: AppConfig(enable = enabled, subsId = BYPASS_SPLASH_SUBS_ID, appId = packageName)
        )
    }

    private fun readPermissionState(): BypassPermissionState {
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val power = app.getSystemService(PowerManager::class.java)
        return BypassPermissionState(
            notificationGranted = notifications,
            ignoringBatteryOptimizations = power?.isIgnoringBatteryOptimizations(app.packageName) == true,
        )
    }

    private fun readMetadata() = BypassRuleMetadata(
        sourceType = runCatching {
            BypassRuleSourceType.valueOf(metadataPrefs.getString("sourceType", "BUNDLED")!!)
        }.getOrDefault(BypassRuleSourceType.BUNDLED),
        bundleVersion = metadataPrefs.getInt("bundleVersion", -1).takeIf { it >= 0 },
        installedAt = metadataPrefs.getLong("installedAt", 0L),
        appCount = metadataPrefs.getInt("appCount", 0),
        groupCount = metadataPrefs.getInt("groupCount", 0),
        ruleCount = metadataPrefs.getInt("ruleCount", 0),
        sha256 = metadataPrefs.getString("sha256", "") ?: "",
        sourceFileName = metadataPrefs.getString("sourceFileName", null),
    )

    private fun saveMetadata(metadata: BypassRuleMetadata) {
        metadataPrefs.edit()
            .putString("sourceType", metadata.sourceType.name)
            .putInt("bundleVersion", metadata.bundleVersion ?: -1)
            .putLong("installedAt", metadata.installedAt)
            .putInt("appCount", metadata.appCount)
            .putInt("groupCount", metadata.groupCount)
            .putInt("ruleCount", metadata.ruleCount)
            .putString("sha256", metadata.sha256)
            .putString("sourceFileName", metadata.sourceFileName)
            .apply()
        metadataFlow.value = metadata
    }

    private fun metadataFor(
        bundle: RawSubscription,
        sourceType: BypassRuleSourceType,
        sourceFileName: String?,
        installedAt: Long = System.currentTimeMillis(),
    ): BypassRuleMetadata {
        val appGroups = bundle.apps.sumOf { it.groups.size }
        val rules = bundle.apps.sumOf { app -> app.groups.sumOf { it.rules.size } } +
            bundle.globalGroups.sumOf { it.rules.size }
        // Hash the exact normalized subscription that is sent to the engine.
        val normalized = li.songe.gkd.util.json.encodeToString(RawSubscription.serializer(), bundle)
        val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return BypassRuleMetadata(
            sourceType = sourceType,
            bundleVersion = bundle.version,
            installedAt = installedAt,
            appCount = bundle.apps.size,
            groupCount = appGroups,
            ruleCount = rules,
            sha256 = hash,
            sourceFileName = sourceFileName,
        )
    }
}
