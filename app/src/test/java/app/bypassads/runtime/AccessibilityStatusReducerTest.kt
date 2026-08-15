package app.bypassads.runtime

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessibilityStatusReducerTest {
    @Test
    fun `connected runtime state wins over a system manager false negative`() {
        val status = AccessibilityStatusReducer.reduce(
            runtimeState = AccessibilityRuntimeState.CONNECTED,
            systemReportsEnabled = false,
            mode = RunMode.SHADOW,
        )

        assertEquals("Shadow 正在观察", status.headline)
        assertTrue(status.isObserving)
    }

    @Test
    fun `off mode reports paused while the service remains connected`() {
        val status = AccessibilityStatusReducer.reduce(
            runtimeState = AccessibilityRuntimeState.CONNECTED,
            systemReportsEnabled = false,
            mode = RunMode.OFF,
        )

        assertEquals("服务已连接 · Shadow 已暂停", status.headline)
        assertFalse(status.isObserving)
    }

    @Test
    fun `unknown and disconnected states never claim active observation`() {
        listOf(AccessibilityRuntimeState.UNKNOWN, AccessibilityRuntimeState.DISCONNECTED).forEach { runtimeState ->
            listOf(false, true).forEach { systemReportsEnabled ->
                val status = AccessibilityStatusReducer.reduce(
                    runtimeState = runtimeState,
                    systemReportsEnabled = systemReportsEnabled,
                    mode = RunMode.SHADOW,
                )

                assertEquals(
                    if (systemReportsEnabled) "系统已启用 · 等待服务连接" else "未确认服务连接",
                    status.headline,
                )
                assertFalse(status.isObserving)
            }
        }
    }
}
