package li.songe.gkd.bypass

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-session action budget. Budgets are keyed by session id, never by
 * package name (WeChat may host several mini-program ads in one package).
 *
 * Every candidate action reserves an attempt BEFORE it is performed, so a
 * failed accessibility action still consumes budget (no infinite retries).
 */
object BypassActionBudget {
    private val used = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Reserve one attempt for [sessionId]. Returns false when the budget
     * ([maxAttempts]) is exhausted and no further action may run.
     */
    fun reserveAttempt(sessionId: String, maxAttempts: Int): Boolean {
        val counter = used.computeIfAbsent(sessionId) { AtomicInteger(0) }
        while (true) {
            val current = counter.get()
            if (current >= maxAttempts) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }

    fun attemptsUsed(sessionId: String): Int = used[sessionId]?.get() ?: 0

    fun reset(sessionId: String) {
        used.remove(sessionId)
    }

    fun clear() {
        used.clear()
    }
}
