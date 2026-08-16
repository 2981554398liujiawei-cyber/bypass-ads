package app.bypassads.core.actuation

import app.bypassads.core.model.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fast Path anchor -> action-target resolution (M2.3 P1-1).
 *
 * The click must land on a strictly verified clickable node: the anchor
 * itself when it is clickable, otherwise a *unique* clickable ancestor that
 * is same-package, enabled, visible, contains the anchor and is not
 * excessively large. No safe target -> null (BLOCK); there is never a
 * fallback to the non-clickable anchor or to coordinate clicks.
 */
class FastPathActionTargetResolverTest {

    private val screenW = 1200
    private val screenH = 2416

    private fun node(
        packageName: String? = "com.sina.weibo",
        clickable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        bounds: IntRect = IntRect(984, 215, 1158, 311),
    ) = FastPathAncestorSnapshot(packageName, clickable, enabled, visible, bounds)

    private val anchor = node(bounds = IntRect(984, 215, 1158, 311))

    @Test
    fun `clickable anchor is itself the action target`() {
        val clickableAnchor = node(clickable = true)

        assertTrue(FastPathActionTargetResolver.anchorIsActionable(clickableAnchor))
        assertFalse(FastPathActionTargetResolver.anchorIsActionable(anchor))
    }

    @Test
    fun `unique verified clickable ancestor resolves`() {
        val parent = node(clickable = true, bounds = IntRect(900, 150, 1180, 380))

        val index = FastPathActionTargetResolver.resolveClickableAncestorIndex(anchor, listOf(parent), screenW, screenH)

        assertEquals(0, index)
    }

    @Test
    fun `no clickable ancestor resolves to null`() {
        val nonClickableParent = node(bounds = IntRect(900, 150, 1180, 380))

        val index = FastPathActionTargetResolver.resolveClickableAncestorIndex(anchor, listOf(nonClickableParent), screenW, screenH)

        assertNull(index)
    }

    @Test
    fun `disabled or invisible ancestor is not a valid action target`() {
        val disabled = node(clickable = true, enabled = false, bounds = IntRect(900, 150, 1180, 380))
        val invisible = node(clickable = true, visible = false, bounds = IntRect(900, 150, 1180, 380))

        assertNull(FastPathActionTargetResolver.resolveClickableAncestorIndex(anchor, listOf(disabled), screenW, screenH))
        assertNull(FastPathActionTargetResolver.resolveClickableAncestorIndex(anchor, listOf(invisible), screenW, screenH))
    }

    @Test
    fun `different package ancestor is not valid`() {
        val otherPkg = node(packageName = "com.tencent.mm", clickable = true, bounds = IntRect(900, 150, 1180, 380))

        assertNull(FastPathActionTargetResolver.resolveClickableAncestorIndex(anchor, listOf(otherPkg), screenW, screenH))
    }

    @Test
    fun `ancestor not containing the anchor is not valid`() {
        val detached = node(clickable = true, bounds = IntRect(100, 100, 400, 300))

        assertNull(FastPathActionTargetResolver.resolveClickableAncestorIndex(anchor, listOf(detached), screenW, screenH))
    }

    @Test
    fun `oversized clickable ancestor is not valid`() {
        val huge = node(clickable = true, bounds = IntRect(0, 0, 1200, 2000)) // area ratio ~0.83 > 0.20

        assertNull(FastPathActionTargetResolver.resolveClickableAncestorIndex(anchor, listOf(huge), screenW, screenH))
    }

    @Test
    fun `multiple qualifying ancestors resolve to null (uniqueness)`() {
        val parent1 = node(clickable = true, bounds = IntRect(900, 150, 1180, 380))
        val parent2 = node(clickable = true, bounds = IntRect(0, 0, 1200, 2416))

        // parent2 is too large, so only parent1 qualifies -> still resolves.
        assertEquals(0, FastPathActionTargetResolver.resolveClickableAncestorIndex(anchor, listOf(parent1, parent2), screenW, screenH))

        // Two qualifying ancestors (both small enough) -> null.
        val parent3 = node(clickable = true, bounds = IntRect(980, 200, 1160, 330))
        assertNull(FastPathActionTargetResolver.resolveClickableAncestorIndex(anchor, listOf(parent1, parent3), screenW, screenH))
    }

    @Test
    fun `disabled or invisible anchor never resolves`() {
        val badAnchor = node(enabled = false)
        val parent = node(clickable = true, bounds = IntRect(900, 150, 1180, 380))

        assertNull(FastPathActionTargetResolver.resolveClickableAncestorIndex(badAnchor, listOf(parent), screenW, screenH))
    }
}
