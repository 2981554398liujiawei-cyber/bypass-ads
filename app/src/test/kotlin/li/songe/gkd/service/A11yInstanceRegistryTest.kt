package li.songe.gkd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-5 (HyperOS destroy/rebind race): an OLD accessibility service instance
 * destroyed after a NEW instance connected must never clear the new instance
 * or flip isRunning=false. Only the CURRENT instance may clear the slot.
 */
class A11yInstanceRegistryTest {

    private class FakeService(val name: String)

    @Test
    fun old_instance_destroy_does_not_clear_new_instance() {
        A11yInstanceRegistry.clearForTest()
        val a = FakeService("A")
        val b = FakeService("B")
        // A connects, then B connects (system rebind while A is being
        // destroyed). The registry holds B.
        A11yInstanceRegistry.connected(a)
        A11yInstanceRegistry.connected(b)
        assertSame(b, A11yInstanceRegistry.currentInstance())
        assertTrue(A11yInstanceRegistry.isRunning.value)
        // A's onDestroy arrives AFTER B connected: must be a no-op.
        A11yInstanceRegistry.destroyed(a)
        assertSame("old instance A must not clear new instance B", b, A11yInstanceRegistry.currentInstance())
        assertTrue("isRunning must stay true while B is alive", A11yInstanceRegistry.isRunning.value)
    }

    @Test
    fun current_instance_destroy_clears_the_slot() {
        A11yInstanceRegistry.clearForTest()
        val a = FakeService("A")
        A11yInstanceRegistry.connected(a)
        assertTrue(A11yInstanceRegistry.isRunning.value)
        A11yInstanceRegistry.destroyed(a)
        assertNull(A11yInstanceRegistry.currentInstance())
        assertFalse(A11yInstanceRegistry.isRunning.value)
    }

    @Test
    fun duplicate_destroy_is_idempotent() {
        A11yInstanceRegistry.clearForTest()
        val a = FakeService("A")
        A11yInstanceRegistry.connected(a)
        A11yInstanceRegistry.destroyed(a)
        A11yInstanceRegistry.destroyed(a)
        assertNull(A11yInstanceRegistry.currentInstance())
        assertFalse(A11yInstanceRegistry.isRunning.value)
    }

    @Test
    fun reconnect_after_destroy_restores_running() {
        A11yInstanceRegistry.clearForTest()
        val a = FakeService("A")
        val b = FakeService("B")
        A11yInstanceRegistry.connected(a)
        A11yInstanceRegistry.destroyed(a)
        assertFalse(A11yInstanceRegistry.isRunning.value)
        // System rebinds a fresh instance:
        A11yInstanceRegistry.connected(b)
        assertSame(b, A11yInstanceRegistry.currentInstance())
        assertTrue(A11yInstanceRegistry.isRunning.value)
        // The long-dead A arriving late still cannot clear B:
        A11yInstanceRegistry.destroyed(a)
        assertSame(b, A11yInstanceRegistry.currentInstance())
        A11yInstanceRegistry.clearForTest()
    }

    @Test
    fun distinct_instances_are_distinguished_by_identity() {
        A11yInstanceRegistry.clearForTest()
        val a1 = FakeService("A")
        val a2 = FakeService("A")
        A11yInstanceRegistry.connected(a1)
        // A second, EQUAL-but-distinct instance destroys: identity check means
        // only the exact current object clears the slot.
        A11yInstanceRegistry.destroyed(a2)
        assertSame(a1, A11yInstanceRegistry.currentInstance())
        A11yInstanceRegistry.clearForTest()
    }

    @Test
    fun created_without_service_connected_still_marks_running() {
        // HyperOS upgrade/rebind may deliver onCreated without a matching
        // onServiceConnected. The registry must still publish running=true so
        // the product UI does not stay on "正在恢复".
        A11yInstanceRegistry.clearForTest()
        val a = FakeService("A")
        A11yInstanceRegistry.connected(a)
        assertSame(a, A11yInstanceRegistry.currentInstance())
        assertTrue(A11yInstanceRegistry.isRunning.value)
        A11yInstanceRegistry.clearForTest()
    }
}
