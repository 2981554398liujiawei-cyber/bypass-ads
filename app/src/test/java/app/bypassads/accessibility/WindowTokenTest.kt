package app.bypassads.accessibility

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Window identity token (M2.2 P1-2): derived from real AccessibilityWindowInfo
 * ids. Identical window count / layout / bounds with different window ids must
 * produce different tokens, so the gate's WINDOW_CHANGED check detects window
 * replacement rather than layout coincidence.
 */
class WindowTokenTest {

    @Test
    fun `same ids in same order produce the same token`() {
        assertEquals(WindowToken.of(listOf(1, 2, 3)), WindowToken.of(listOf(1, 2, 3)))
    }

    @Test
    fun `different window ids with the same count produce different tokens`() {
        // The replacement case: one window replaced by another, count stays 1.
        assertNotEquals(WindowToken.of(listOf(10)), WindowToken.of(listOf(11)))
    }

    @Test
    fun `window order change rotates the token`() {
        assertNotEquals(WindowToken.of(listOf(1, 2)), WindowToken.of(listOf(2, 1)))
    }

    @Test
    fun `empty window set has a stable empty token`() {
        assertEquals(0L, WindowToken.of(emptyList()))
    }
}
