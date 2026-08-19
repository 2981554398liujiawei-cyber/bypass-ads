package li.songe.gkd.bypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
                groupCount = bundle?.apps?.sumOf { it.groups.size }
                    ?.plus(bundle.globalGroups.size) ?: 0,
                ruleCount = bundle?.apps?.sumOf { app ->
                    app.groups.sumOf { group -> group.rules.size }
                }?.plus(bundle.globalGroups.sumOf { group -> group.rules.size }) ?: 0,
            )
        }.stateIn(appScope, SharingStarted.Eagerly, BypassRuleStats())

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

    override fun requestServiceRecovery() {
        appScope.launchTry(Dispatchers.IO) { fixRestartAutomatorService() }
    }

    override suspend fun importLocalRules(source: String): BypassImportResult {
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
            version = (current?.version ?: 0) + 1,
            apps = splashApps,
            // Never accept external global rules in this product: retain only
            // our known conservative fallback from the active Bypass bundle.
            globalGroups = current?.globalGroups ?: emptyList(),
        )
        updateSubscription(imported)
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
        val currentVersion = subsMapFlow.value[BYPASS_SPLASH_SUBS_ID]?.version ?: 0
        updateSubscription(restored.copy(id = BYPASS_SPLASH_SUBS_ID, version = currentVersion + 1))
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
}
