package li.songe.gkd.bypass

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
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
    @Volatile
    private var packageName = ""
    @Volatile
    private var runnableGroups = 0
    @Volatile
    private var selectorQueries = 0
    @Volatile
    private var tabStartedAt = 0L
    @Volatile
    private var detailStartedAt = 0L
    private var completedActionSamples = 0
    private var totalActionLatencyMs = 0L
    val averageActionLatencyMs = MutableStateFlow<Int?>(null)

    fun appSwitch() { if (META.debuggable) t0 = SystemClock.elapsedRealtime() }
    fun matcherStarted(appId: String, groups: Int) {
        if (META.debuggable) {
            packageName = appId
            runnableGroups = groups
            selectorQueries = 0
        }
    }
    fun selectorQueried() { if (META.debuggable) selectorQueries++ }
    fun matched(rule: String) {
        if (META.debuggable) {
            t1 = SystemClock.elapsedRealtime()
            ruleName = rule.take(160)
            Log.d(TAG, "package=$packageName runnableGroups=$runnableGroups selectorQueries=$selectorQueries T0=$t0 T1=$t1 T0→T1=${t1 - t0}ms rule=$ruleName")
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
            Log.d(TAG, "package=$packageName runnableGroups=$runnableGroups selectorQueries=$selectorQueries T3=$t3 T2→T3=${t3 - t2}ms T1→T3=${t3 - t1}ms T0→T1=${t1 - t0}ms rule=${rule.take(160)}")
        }
    }
    fun actionSucceeded() {
        if (META.debuggable && t0 > 0L) {
            completedActionSamples++
            totalActionLatencyMs += (SystemClock.elapsedRealtime() - t0).coerceAtLeast(0L)
            averageActionLatencyMs.value = (totalActionLatencyMs / completedActionSamples).toInt()
        }
    }
    fun tabSwitchStart(tab: String) {
        if (META.debuggable) {
            tabStartedAt = SystemClock.elapsedRealtime()
            Log.d(TAG, "TAB_SWITCH_START tab=$tab t=$tabStartedAt")
        }
    }
    fun tabSwitchFirstFrame(tab: String) {
        if (META.debuggable && tabStartedAt > 0L) {
            val now = SystemClock.elapsedRealtime()
            Log.d(TAG, "TAB_SWITCH_FIRST_FRAME tab=$tab duration=" + (now - tabStartedAt) + "ms")
            tabStartedAt = 0L
        }
    }
    fun detailNavigationStart(route: String) {
        if (META.debuggable) {
            detailStartedAt = SystemClock.elapsedRealtime()
            Log.d(TAG, "DETAIL_NAV_START route=$route t=$detailStartedAt")
        }
    }
    fun detailNavigationFirstFrame(route: String) {
        if (META.debuggable && detailStartedAt > 0L) {
            val now = SystemClock.elapsedRealtime()
            Log.d(TAG, "DETAIL_NAV_FIRST_FRAME route=$route duration=" + (now - detailStartedAt) + "ms")
            detailStartedAt = 0L
        }
    }
}

private object SystemClock { fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime() }
