package app.bypassads.actuation

import app.bypassads.BuildConfig
import app.bypassads.core.actuation.ActuationCapabilityPolicy
import app.bypassads.core.actuation.BuildVariant

/**
 * M2.2 build-variant capability wiring: the real actuation capability only
 * exists in the `experimental` build type (BuildConfig.ACTIVE_EXPERIMENTAL).
 * Every other variant — debug and release/RC — compiles with
 * `actuatorAllowed == false` and fails fast on any actuation attempt.
 */
object ExperimentalCapability {
    val policy: ActuationCapabilityPolicy = ActuationCapabilityPolicy(
        if (BuildConfig.ACTIVE_EXPERIMENTAL) BuildVariant.EXPERIMENTAL else BuildVariant.RELEASE_RC,
    )

    val isExperimentalBuild: Boolean get() = BuildConfig.ACTIVE_EXPERIMENTAL
}
