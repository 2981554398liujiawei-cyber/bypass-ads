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
        }

    fun activeEnabledAtEpochMs(): Long = prefs.getLong(KEY_ENABLED_AT, 0L)

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
    }
}
