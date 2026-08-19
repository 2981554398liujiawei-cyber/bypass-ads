package li.songe.gkd.bypass

import android.util.Log
import li.songe.gkd.META

/** Debug/self-use timing only. It never writes user data to the database or UI. */
object BypassPerfTrace {
    private const val TAG = "BypassPerfTrace"
    private var t0 = 0L

    fun appSwitch() { if (META.debuggable) t0 = SystemClock.elapsedRealtime() }
    fun matched(rule: String) {
        if (META.debuggable) Log.d(TAG, "T0=$t0 T1=${SystemClock.elapsedRealtime()} rule=$rule")
    }
    fun actionStarted(rule: String) { if (META.debuggable) Log.d(TAG, "T2=${SystemClock.elapsedRealtime()} rule=$rule") }
    fun actionFinished(rule: String) { if (META.debuggable) Log.d(TAG, "T3=${SystemClock.elapsedRealtime()} rule=$rule") }
}

private object SystemClock { fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime() }
