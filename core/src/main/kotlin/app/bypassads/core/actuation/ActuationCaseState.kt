package app.bypassads.core.actuation

/**
 * Per-case actuation lifecycle (M2.1 task 2, ADR-0002 §5).
 *
 * Enforces the real one-attempt-per-case rule: `attemptCount` lives here and
 * only moves 0 -> 1 after the gate authorizes; any `attemptCount >= 1` blocks
 * further attempts. `status` transitions ACTIVE -> ATTEMPTED (on authorized
 * attempt) -> COMPLETED (SUCCESS) or FAILED (NO_EFFECT / UNCERTAIN). A new
 * case requires a new state instance; no fake case can reset these counters.
 */
class ActuationCaseState(
    val caseId: String,
    val generation: Long,
    val createdAtElapsedMs: Long,
) {
    var attemptCount: Int = 0
        private set

    var status: ActuationCaseStatus = ActuationCaseStatus.ACTIVE
        private set

    val canAttempt: Boolean
        get() = status == ActuationCaseStatus.ACTIVE && attemptCount < MAX_ACTUATION_ATTEMPTS_PER_CASE

    /** Called only after the gate returned Allow. */
    fun recordAuthorizedAttempt() {
        require(status == ActuationCaseStatus.ACTIVE) { "case $caseId is $status; attempts are not allowed" }
        require(attemptCount < MAX_ACTUATION_ATTEMPTS_PER_CASE) { "case $caseId already attempted (limit $MAX_ACTUATION_ATTEMPTS_PER_CASE)" }
        attemptCount++
        status = ActuationCaseStatus.ATTEMPTED
    }

    /** Closes the case from the post-action outcome: SUCCESS completes, everything else fails (no retry). */
    fun finish(outcome: ActuationOutcome) {
        status = when (outcome) {
            ActuationOutcome.SUCCESS -> ActuationCaseStatus.COMPLETED
            ActuationOutcome.NO_EFFECT, ActuationOutcome.UNCERTAIN -> ActuationCaseStatus.FAILED
        }
    }
}

enum class ActuationCaseStatus {
    ACTIVE,
    ATTEMPTED,
    COMPLETED,
    FAILED,
}
