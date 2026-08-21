package li.songe.gkd.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * P1-5 (HyperOS destroy/rebind race): single-owner slot for the live
 * accessibility service instance.
 *
 * The system can destroy the OLD service instance A and bind a NEW instance
 * B in quick succession (HyperOS accessibility recovery). Without ownership
 * semantics, A.onDestroy() would clear B's instance and set isRunning=false
 * even though B is alive and working — the UI then shows "not running" until
 * the process restarts.
 *
 * This registry is the single owner: [connected] records the CURRENT
 * instance, [destroyed] only clears when the dying instance IS the current
 * one. Pure Kotlin + kotlinx.coroutines, so the race semantics are covered by
 * JVM unit tests.
 */
object A11yInstanceRegistry {

    @Volatile
    private var current: Any? = null

    private val running = MutableStateFlow(false)

    /** Whether the accessibility service is currently live. */
    val isRunning: StateFlow<Boolean> = running

    /** The live service instance, or null. */
    fun currentInstance(): Any? = current

    /** A service connected and became the current live instance. */
    fun <T : Any> connected(instance: T): T {
        current = instance
        running.value = true
        return instance
    }

    /**
     * A service instance was destroyed. Only the CURRENT instance may clear
     * the slot: an old instance A being destroyed after B connected must
     * NEVER clear B.
     */
    fun <T : Any> destroyed(instance: T) {
        if (current === instance) {
            current = null
            running.value = false
        }
    }

    /** Test hook. */
    internal fun clearForTest() {
        current = null
        running.value = false
    }
}
