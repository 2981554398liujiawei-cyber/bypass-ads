package li.songe.gkd.bypass

import kotlinx.coroutines.flow.StateFlow

/**
 * Bypass Ads product facade.
 *
 * The product UI talks only to this interface. Everything GKD-specific
 * (subscriptions, rule resolution, accessibility service, stores, DAOs) stays
 * behind the adapter implementation, so the normal user flow never exposes
 * GKD concepts (subs, selector, route, matcher, ...).
 */
interface BypassEngine {

    /** Translated accessibility/service status for the home screen. */
    val serviceState: StateFlow<BypassServiceState>

    /** Master matching switch. */
    val masterEnabled: StateFlow<Boolean>

    /** Product-level advertising categories backed by real rule groups. */
    val adCategories: StateFlow<List<BypassAdCategoryState>>

    /** Coverage stats of the bundled splash rule set. */
    val stats: StateFlow<BypassRuleStats>

    /** Persisted provenance for the rule package currently selected by the user. */
    val ruleMetadata: StateFlow<BypassRuleMetadata>

    /** Android permission and background-execution state shown in product UI. */
    val permissionState: StateFlow<BypassPermissionState>

    /** Most recent skip action (null when nothing skipped yet). */
    val latestAction: StateFlow<BypassActionRecord?>

    /** Recent skip actions (newest first). */
    val recentActions: StateFlow<List<BypassActionRecord>>

    /** Number of successful actions kept in the Bypass Ads local history. */
    val skipCount: StateFlow<Int>

    /** Conservative generic splash fallback switch (default on). */
    val genericFallbackEnabled: StateFlow<Boolean>

    /** Whether a successful skip should show the branded toast. */
    val actionToastEnabled: StateFlow<Boolean>

    /** Whether the ongoing service notification is enabled. */
    val persistentNotificationEnabled: StateFlow<Boolean>

    fun setMasterEnabled(enabled: Boolean)

    fun setAdCategoryEnabled(category: BypassAdCategory, enabled: Boolean)

    fun setGenericFallbackEnabled(enabled: Boolean)

    fun setActionToastEnabled(enabled: Boolean)

    fun setPersistentNotificationEnabled(enabled: Boolean)

    /** Refresh system-controlled permission state after returning from Settings. */
    fun refreshPermissionState()

    /** Try the existing internal recovery path; callers still offer the
     * system accessibility screen when Android requires user consent. */
    fun requestServiceRecovery()

    suspend fun importLocalRules(source: String, sourceFileName: String? = null): BypassImportResult

    suspend fun restoreBundledRules(): BypassImportResult

    suspend fun clearRecentActions()

    /** Apps that currently have at least one splash rule in the bundled set. */
    suspend fun getProtectedApps(): List<BypassAppInfo>

    suspend fun getAppEnabled(packageName: String): Boolean

    suspend fun setAppEnabled(packageName: String, enabled: Boolean)

    suspend fun getAppDetail(packageName: String): BypassAppDetail?

    suspend fun setRuleGroupEnabled(packageName: String, groupKey: Int, enabled: Boolean)

    suspend fun getSubscriptions(): List<BypassSubscriptionInfo>
}

enum class BypassAdCategory {
    SPLASH,
    IN_APP_FULLSCREEN,
    MARKETING_POPUP,
    OTHER_CLOSABLE,
}

data class BypassAdCategoryState(
    val category: BypassAdCategory,
    val enabled: Boolean,
    val appCount: Int,
    val groupCount: Int,
    val ruleCount: Int,
) {
    val title: String
        get() = when (category) {
            BypassAdCategory.SPLASH -> "自动跳过开屏广告"
            BypassAdCategory.IN_APP_FULLSCREEN -> "全屏 / 插屏广告"
            BypassAdCategory.MARKETING_POPUP -> "营销弹窗"
            BypassAdCategory.OTHER_CLOSABLE -> "其它可关闭广告"
        }

    val description: String
        get() = when (category) {
            BypassAdCategory.SPLASH -> "启动应用时自动关闭开屏广告"
            BypassAdCategory.IN_APP_FULLSCREEN -> "关闭遮挡整个界面的广告"
            BypassAdCategory.MARKETING_POPUP -> "关闭活动、推广、会员等营销内容"
            BypassAdCategory.OTHER_CLOSABLE -> "局部与分段广告，可能影响原有操作体验"
        }
}

enum class BypassServiceStatus {
    NORMAL,
    RECOVERING,
    OFF,
    NEED_AUTHORIZATION,
}

data class BypassServiceState(val status: BypassServiceStatus) {
    val label: String
        get() = when (status) {
            BypassServiceStatus.NORMAL -> "正常运行"
            BypassServiceStatus.RECOVERING -> "正在恢复"
            BypassServiceStatus.OFF -> "未开启"
            BypassServiceStatus.NEED_AUTHORIZATION -> "需要授权"
        }

    val description: String
        get() = when (status) {
            BypassServiceStatus.NORMAL -> "无障碍服务已连接，开屏广告保护正常运行。"
            BypassServiceStatus.RECOVERING -> "系统正在恢复无障碍服务，保护会很快恢复。"
            BypassServiceStatus.OFF -> "尚未开启保护。"
            BypassServiceStatus.NEED_AUTHORIZATION -> "需要授予无障碍权限，Bypass Ads 才能自动跳过开屏广告。"
        }
}

data class BypassRuleStats(
    val appCount: Int = 0,
    val groupCount: Int = 0,
    val ruleCount: Int = 0,
)

enum class BypassRuleSourceType { BUNDLED, LOCAL_IMPORT }

data class BypassRuleMetadata(
    val sourceType: BypassRuleSourceType = BypassRuleSourceType.BUNDLED,
    val bundleVersion: Int? = null,
    val installedAt: Long = 0L,
    val appCount: Int = 0,
    val groupCount: Int = 0,
    val ruleCount: Int = 0,
    val sha256: String = "",
    val sourceFileName: String? = null,
)

data class BypassPermissionState(
    val notificationGranted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
)

data class BypassActionRecord(
    val appId: String,
    val appName: String?,
    val groupName: String?,
    val time: Long,
)

data class BypassAppInfo(
    val packageName: String,
    val appName: String,
    val enabled: Boolean,
    val groupCount: Int,
    val ruleCount: Int = 0,
)

data class BypassRuleGroupInfo(
    val key: Int,
    val name: String,
    val category: BypassAdCategory,
    val enabled: Boolean,
    val ruleCount: Int,
)

data class BypassAppDetail(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val enabled: Boolean,
    val groups: List<BypassRuleGroupInfo>,
)

data class BypassSubscriptionInfo(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val appCount: Int,
    val groupCount: Int,
    val ruleCount: Int,
)

data class BypassImportResult(val accepted: Boolean, val message: String)
