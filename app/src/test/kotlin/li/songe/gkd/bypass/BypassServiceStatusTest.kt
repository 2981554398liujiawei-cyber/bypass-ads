package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-5: product a11y status. HyperOS can keep the service Bound after an
 * upgrade/rebind without delivering onServiceConnected, so authorized +
 * system-bound is NORMAL even when the in-process instance flag is still
 * false. RECOVERING is only for "authorized but not yet bound".
 */
class BypassServiceStatusTest {

    @Test
    fun instance_running_is_normal() {
        assertEquals(
            BypassServiceStatus.NORMAL,
            resolveBypassServiceStatus(instanceRunning = true, authorized = true, systemBound = true),
        )
        assertEquals(
            BypassServiceStatus.NORMAL,
            resolveBypassServiceStatus(instanceRunning = true, authorized = true, systemBound = false),
        )
    }

    @Test
    fun authorized_and_system_bound_without_instance_is_recovering() {
        assertEquals(
            BypassServiceStatus.RECOVERING,
            resolveBypassServiceStatus(instanceRunning = false, authorized = true, systemBound = true),
        )
    }

    @Test
    fun authorized_and_a11y_enabled_without_instance_is_recovering() {
        assertEquals(
            BypassServiceStatus.RECOVERING,
            resolveBypassServiceStatus(
                instanceRunning = false,
                authorized = true,
                systemBound = false,
                accessibilityEnabled = true,
            ),
        )
    }

    @Test
    fun authorized_but_not_bound_and_a11y_off_is_recovering() {
        assertEquals(
            BypassServiceStatus.RECOVERING,
            resolveBypassServiceStatus(
                instanceRunning = false,
                authorized = true,
                systemBound = false,
                accessibilityEnabled = false,
            ),
        )
    }

    @Test
    fun not_authorized_is_need_authorization() {
        assertEquals(
            BypassServiceStatus.NEED_AUTHORIZATION,
            resolveBypassServiceStatus(instanceRunning = false, authorized = false, systemBound = false),
        )
    }
}
