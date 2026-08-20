package li.songe.gkd.bypass

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Product and product-adjacent pages all live in MainViewModel.backStack. */
@Serializable data object BypassHomeRoute : NavKey
@Serializable data object BypassAdsRoute : NavKey
@Serializable data object BypassRecordsRoute : NavKey
@Serializable data object BypassSettingsRoute : NavKey
@Serializable data object BypassRuntimeProtectionRoute : NavKey
@Serializable data object BypassNotificationManagementRoute : NavKey
@Serializable data object BypassSplashStrategyRoute : NavKey
@Serializable data object BypassAppControlRoute : NavKey
@Serializable data object BypassRulesSubscriptionRoute : NavKey
@Serializable data object BypassRuleDetailRoute : NavKey
@Serializable data class BypassAppDetailRoute(val packageName: String) : NavKey
@Serializable data class BypassFailureDetailRoute(val eventId: Long) : NavKey
@Serializable data object BypassPromptSettingsRoute : NavKey
@Serializable data object BypassBackupRoute : NavKey
@Serializable data object BypassAdvancedToolsRoute : NavKey
@Serializable data object BypassAdvancedRulesRoute : NavKey
@Serializable data object BypassDiagnosticsRoute : NavKey
@Serializable data object BypassTeachRoute : NavKey
@Serializable data object BypassFullToolsRoute : NavKey
@Serializable data object BypassAboutRoute : NavKey
@Serializable data object BypassLicensesRoute : NavKey

/** Root selection is state, not navigation history. */
enum class BypassRootTab {
    HOME,
    ADS,
    RECORDS,
    SETTINGS,
}
