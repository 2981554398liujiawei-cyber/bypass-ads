package app.bypassads.experimental

import android.content.Context
import android.content.SharedPreferences
import java.io.Closeable

/**
 * Persisted experimental trial state (M2.2).
 *
 * Privacy minimal by design: stores only booleans/timestamps and a short
 * verdict string — never node text, screenshots or user content, and nothing
 * is uploaded. The enabled flag doubles as the global kill switch: OFF stops
 * the actuator immediately and leaves Shadow untouched.
 */
class ExperimentalSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Kill switch / enable flag. Default OFF; installing the APK never enables it. */
    var activeExperimentalEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit()
                .putBoolean(KEY_ENABLED, value)
                .putLong(KEY_ENABLED_AT, if (value) System.currentTimeMillis() else 0L)
                .apply()
            // Re-arming (user explicitly confirming ON) grants a fresh 20-action
            // session; a service/process rebuild only restores the remaining budget.
            if (value) {
                prefs.edit().putInt(KEY_BUDGET, DEFAULT_ACTION_BUDGET).apply()
            }
        }

    fun activeEnabledAtEpochMs(): Long = prefs.getLong(KEY_ENABLED_AT, 0L)

    /** Remaining per-session action budget, persisted so service rebuilds cannot refill it. */
    var actionBudgetRemaining: Int
        get() = prefs.getInt(KEY_BUDGET, DEFAULT_ACTION_BUDGET)
        set(value) {
            prefs.edit().putInt(KEY_BUDGET, value.coerceIn(0, DEFAULT_ACTION_BUDGET)).apply()
        }

    /** Last actuation verdict for the experimental page, e.g. "BLOCK:CTA_RISK" or "ALLOW:UNCERTAIN". */
    var lastAction: String
        get() = prefs.getString(KEY_LAST_ACTION, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_ACTION, value).apply()
        }

    fun observe(listener: () -> Unit): Closeable {
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> listener() }
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        return Closeable { prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
    }

    private companion object {
        const val PREFERENCES_NAME = "bypass_ads_experimental"
        const val KEY_ENABLED = "active_experimental_enabled"
        const val KEY_ENABLED_AT = "active_experimental_enabled_at"
        const val KEY_LAST_ACTION = "last_action"
        const val KEY_BUDGET = "action_budget_remaining"
        const val DEFAULT_ACTION_BUDGET = 20
    }
}
