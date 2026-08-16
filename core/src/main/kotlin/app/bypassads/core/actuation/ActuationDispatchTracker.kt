package app.bypassads.core.actuation

/**
 * M2.2 P1: per-attempt dispatch state shared between the actuator and the
 * service's passive-verification scheduling.
 *
 * `dispatchReported == null` means **no click was dispatched in this attempt**
 * (0 or >1 live matches, or `performAction` never returned). Passive
 * post-action verification must then NOT be scheduled, and diagnostics must
 * never inherit a stale value from a previous attempt.
 *
 * `dispatchReported == false` still counts as "a click was dispatched": on
 * HyperOS `performAction` can report false while the action takes effect, so
 * `false` must still enter passive verification.
 */
class ActuationDispatchTracker {
    var dispatchReported: Boolean? = null
        private set

    /** Call at the very start of every actuation attempt; clears any previous dispatch state. */
    fun beginAttempt() {
        dispatchReported = null
    }

    /** Call exactly once when ACTION_CLICK was actually invoked on the live node. */
    fun recordDispatch(reported: Boolean) {
        dispatchReported = reported
    }

    /** Passive post-action verification is warranted only when a click really was dispatched. */
    val verificationWarranted: Boolean get() = dispatchReported != null
}
