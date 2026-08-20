package li.songe.gkd.bypass

import android.Manifest
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.songe.gkd.service.A11yService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the same enhanced-control path the Home switch calls, without
 * depending on Compose idleness on HyperOS.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityControlInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun enhancedControl_can_enable_and_disable_accessibility_service() {
        assertEquals(
            "WRITE_SECURE_SETTINGS is required for this device test",
            android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS),
        )

        val initiallyEnabled = isServiceEnabled()
        try {
            if (initiallyEnabled) {
                GkdBypassEngine.setAccessibilityEnabled(false)
                assertTrue("service was not disabled through secure settings", waitFor(false))
            }

            GkdBypassEngine.setAccessibilityEnabled(true)
            assertTrue("service was not enabled through secure settings", waitFor(true))
            // Enabling holds the service-restart mutex for two seconds. The
            // Home UI does not expose an ON state before this has completed.
            Thread.sleep(2_500)

            GkdBypassEngine.setAccessibilityEnabled(false)
            assertTrue("service was not disabled through secure settings", waitFor(false))
        } finally {
            if (isServiceEnabled() != initiallyEnabled) {
                // If an assertion interrupted an in-flight operation, wait for
                // its mutex-protected restart work before restoring the device.
                Thread.sleep(2_500)
                GkdBypassEngine.setAccessibilityEnabled(initiallyEnabled)
                assertTrue("original accessibility state was not restored", waitFor(initiallyEnabled))
            }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return services.split(':').any { it == A11yService.a11yCn.flattenToString() }
    }

    private fun waitFor(enabled: Boolean): Boolean {
        repeat(100) {
            if (isServiceEnabled() == enabled) return true
            Thread.sleep(100)
        }
        return false
    }
}
