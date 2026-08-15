package app.bypassads.core.actuation

/** Outcome of the gate evaluation (ADR-0002 §3). */
sealed interface ActuationVerdict {
    /** Authorization to actuate the [app.bypassads.core.actuation.TargetFingerprint]. */
    data class Allow(val fingerprint: TargetFingerprint) : ActuationVerdict

    /** Refusal with a concrete reason. */
    data class Block(val reason: ActuationBlockReason) : ActuationVerdict
}
