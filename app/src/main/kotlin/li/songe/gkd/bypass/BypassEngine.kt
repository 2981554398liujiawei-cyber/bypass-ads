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

    /** Master "自动跳过开屏广告" switch. */
    val masterEnabled: StateFlow<Boolean>

    /** Coverage stats of the bundled splash rule set. */
    val stats: StateFlow<BypassRuleStats>

    /** Most recent skip action (null when nothing skipped yet). */
    val latestAction: StateFlow<BypassActionRecord?>

    /** Recent skip actions (newest first). */
    val recentActions: StateFlow<List<BypassActionRecord>>

    /** Conservative generic splash fallback switch (default on). */
    val genericFallbackEnabled: StateFlow<Boolean>

    fun setMasterEnabled(enabled: Boolean)

    fun setGenericFallbackEnabled(enabled: Boolean)

    /** Apps that currently have at least one splash rule in the bundled set. */
    suspend fun getProtectedApps(): List<BypassAppInfo>

    suspend fun getAppEnabled(packageName: String): Boolean

    suspend fun setAppEnabled(packageName: String, enabled: Boolean)
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
)
