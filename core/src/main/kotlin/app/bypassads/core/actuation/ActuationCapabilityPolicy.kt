package app.bypassads.core.actuation

/**
 * Product-level capability freeze (M2.1 P2, GPT audit suggestion).
 *
 * The real click capability exists since M2.1 (AndroidNodeActuator), so the
 * build variant itself must decide whether actuation may ever be enabled:
 * - `RELEASE_RC` builds: **Shadow only** — actuation is always off;
 * - `EXPERIMENTAL` builds: gated actuator may be wired (M2.2+).
 *
 * This is a guard against a future careless wiring
 * (`service.onWindowChanged() -> session.execute()`) bypassing product state
 * design: any entry point must consult this policy first.
 */
enum class BuildVariant {
    RELEASE_RC,
    EXPERIMENTAL,
}

class ActuationCapabilityPolicy(
    private val variant: BuildVariant,
) {
    /** Shadow-only builds never allow actuation, regardless of gate verdicts. */
    val actuatorAllowed: Boolean
        get() = variant == BuildVariant.EXPERIMENTAL

    /** Fails fast when actuation is attempted on a non-experimental build. */
    fun requireActuatorAllowed() {
        check(actuatorAllowed) {
            "actuation capability is disabled for $variant build (Shadow only)"
        }
    }
}
