package li.songe.gkd.bypass

import android.util.Log
import li.songe.gkd.META

/** Debug/self-use timing only. It never writes user data to the database or UI. */
object BypassPerfTrace {
    private const val TAG = "BypassPerfTrace"
    @Volatile
    private var t0 = 0L
    @Volatile
    private var t1 = 0L
    @Volatile
    private var t2 = 0L
    @Volatile
    private var ruleName = ""

    fun appSwitch() { if (META.debuggable) t0 = SystemClock.elapsedRealtime() }
    fun matched(rule: String) {
        if (META.debuggable) {
            t1 = SystemClock.elapsedRealtime()
            ruleName = rule.take(160)
            Log.d(TAG, "T0=$t0 T1=$t1 T0→T1=${t1 - t0}ms rule=$ruleName")
        }
    }
    fun actionStarted(rule: String) {
        if (META.debuggable) {
            t2 = SystemClock.elapsedRealtime()
            Log.d(TAG, "T2=$t2 T1→T2=${t2 - t1}ms rule=${rule.take(160)}")
        }
    }
    fun actionFinished(rule: String) {
        if (META.debuggable) {
            val t3 = SystemClock.elapsedRealtime()
            Log.d(TAG, "T3=$t3 T2→T3=${t3 - t2}ms T1→T3=${t3 - t1}ms T0→T1=${t1 - t0}ms rule=${rule.take(160)}")
        }
    }
}

private object SystemClock { fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime() }
