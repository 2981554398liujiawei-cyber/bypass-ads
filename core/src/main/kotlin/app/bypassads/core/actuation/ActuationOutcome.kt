package app.bypassads.core.actuation

/**
 * Post-action outcome contract (ADR-0002 §5). Defined in M2.0; executed by a
 * future actuator (M2.1+) only.
 */
enum class ActuationOutcome {
    /** Original target disappeared / splash window gone / clearly non-ad state. */
    SUCCESS,

    /** Target and window are still present and unchanged — no effect. */
    NO_EFFECT,

    /** Evidence is insufficient to classify success or no-effect. */
    UNCERTAIN,
}

/**
 * At most one actuation attempt per case. `NO_EFFECT` and `UNCERTAIN` are
 * never auto-retried; this removes the "clicked wrong, retry aggressively"
 * failure mode.
 */
const val MAX_ACTUATION_ATTEMPTS_PER_CASE: Int = 1

object ActuationOutcomeClassifier {
    fun classify(
        targetDisappeared: Boolean,
        splashWindowGone: Boolean,
        nonAdStateVisible: Boolean,
        targetStillPresent: Boolean,
        windowStillPresent: Boolean,
    ): ActuationOutcome = when {
        targetDisappeared || splashWindowGone || nonAdStateVisible -> ActuationOutcome.SUCCESS
        targetStillPresent && windowStillPresent -> ActuationOutcome.NO_EFFECT
        else -> ActuationOutcome.UNCERTAIN
    }
}
