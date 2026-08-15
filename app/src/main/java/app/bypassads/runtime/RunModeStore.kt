package app.bypassads.runtime

import android.content.Context

class RunModeStore(context: Context) {
    private val prefs = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    fun get(): RunMode = runCatching {
        RunMode.valueOf(prefs.getString(KEY, RunMode.SHADOW.name) ?: RunMode.SHADOW.name)
    }.getOrDefault(RunMode.SHADOW)

    fun set(mode: RunMode) {
        prefs.edit().putString(KEY, mode.name).apply()
    }

    fun markServiceConnected(epochMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(LAST_CONNECTED, epochMs).apply()
    }

    fun lastServiceConnected(): Long = prefs.getLong(LAST_CONNECTED, 0L)

    private companion object {
        const val KEY = "run_mode"
        const val LAST_CONNECTED = "last_service_connected"
    }
}
