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
    fun authorized_and_system_bound_is_normal_even_without_instance_flag() {
        // The HyperOS cover-install / recovery case: dumpsys Bound, settings
        // still authorized, but onServiceConnected was not re-delivered.
        assertEquals(
            BypassServiceStatus.NORMAL,
            resolveBypassServiceStatus(instanceRunning = false, authorized = true, systemBound = true),
        )
    }

    @Test
    fun authorized_and_a11y_enabled_is_normal_even_without_bound_list() {
        // HyperOS getEnabledAccessibilityServiceList can be empty while dumpsys
        // already shows Bound. Authorized + accessibility enabled => NORMAL.
        assertEquals(
            BypassServiceStatus.NORMAL,
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
