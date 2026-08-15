package app.bypassads.core.actuation

/**
 * Node actuator abstraction (M2.1 task 3).
 *
 * A [NodeActuator] never judges safety — it only executes. Its entry point is
 * typed to [ActuationVerdict.Allow] so a non-Allow verdict cannot even be
 * expressed at the call site; the single allowed path is
 * `FreshTargetResolver -> ActuationGate -> Allow -> NodeActuator -> performAction`.
 *
 * At most one attempt per case: the caller ([ActuationSession]) enforces
 * `MAX_ACTUATION_ATTEMPTS_PER_CASE`; execution failure is never re-clicked.
 */
interface NodeActuator {
    fun actuate(
        verdict: ActuationVerdict.Allow,
        resolution: FreshTargetResolution,
    ): ActuationOutcome
}
