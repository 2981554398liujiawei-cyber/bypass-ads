package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Strategy-gate matrix on pure logic. Mirrors the testad scene matrix so the
 * same expectations run in both JVM tests and on-device ad scenes.
 *
 * Scene mapping (testad):
 *  A text=跳过        -> skip
 *  B desc=跳过        -> skip
 *  D text=关闭        -> close text
 *  E desc=关闭广告     -> close desc
 *  F vid=ad_close     -> close view id
 *  G text=×           -> close icon
 *  H structural X     -> structural close
 *  I coordinate-only  -> coordinate fallback
 *  W ordinary close   -> not an exit (too large / non-ad)
 */
class BypassStrategyMatrixTest {

    private fun policyFor(ordinal: Int) = BypassAdStrategyMode.from(ordinal).policy

    @Test
    fun conservative_only_allows_skip() {
        val p = policyFor(0)
        assertNull("skip should be allowed", gate(null, p, 80, 40))
        assertNotNull("close text rejected in conservative", gateCloseText(p))
        assertNotNull("close view id rejected in conservative", gateCloseViewId(p))
        assertNotNull("X rejected in conservative", gateCloseIcon(p))
    }

    @Test
    fun aggressive_allows_close_text_viewid_and_icon() {
        val p = policyFor(1)
        assertNull(gateCloseText(p))
        assertNull(gateCloseViewId(p))
        assertNull(gateCloseIcon(p))
        assertNotNull("coordinate still rejected in aggressive", gateCoordinate(p))
    }

    @Test
    fun crazy_allows_everything_within_window() {
        val p = policyFor(2)
        assertNull(gateCloseText(p))
        assertNull(gateCloseViewId(p))
        assertNull(gateCloseIcon(p))
        assertNull(gateCoordinate(p))
    }

    @Test
    fun oversized_candidate_rejected_in_every_mode() {
        for (ordinal in 0..2) {
            val p = policyFor(ordinal)
            // 520x320 control is never a close button.
            assertEquals(BypassRejectReason.TOO_LARGE, gate(null, p, 520, 320))
        }
    }

    @Test
    fun high_risk_app_blocks_generic_fallback_but_allows_dedicated() {
        for (ordinal in 0..2) {
            val p = policyFor(ordinal)
            assertEquals(
                BypassRejectReason.SENSITIVE_ACTIVITY,
                BypassStrategyGate.rejectReason(
                    candidate = BypassExitCandidateType.CLOSE_TEXT,
                    packageName = "com.eg.android.AlipayGphone",
                    activityName = "com.alipay.XRiverActivity",
                    nodeWidth = 80,
                    nodeHeight = 40,
                    appHasDedicatedRule = false,
                    inWindow = true,
                    policy = p,
                ),
            )
            // Dedicated precise rule on the same host is still allowed.
            assertNull(
                BypassStrategyGate.rejectReason(
                    candidate = BypassExitCandidateType.CLOSE_TEXT,
                    packageName = "com.eg.android.AlipayGphone",
                    activityName = "com.alipay.XRiverActivity",
                    nodeWidth = 80,
                    nodeHeight = 40,
                    appHasDedicatedRule = true,
                    inWindow = true,
                    policy = p,
                ),
            )
        }
    }

    private fun gate(candidate: BypassExitCandidateType?, p: BypassStrategyPolicy, w: Int, h: Int): String? =
        BypassStrategyGate.rejectReason(
            candidate = candidate ?: BypassExitCandidateType.SKIP_TEXT,
            packageName = "com.example.app",
            activityName = "com.example.MainActivity",
            nodeWidth = w,
            nodeHeight = h,
            inWindow = true,
            policy = p,
        )

    private fun gateCloseText(p: BypassStrategyPolicy): String? =
        gate(BypassExitCandidateType.CLOSE_TEXT, p, 80, 40)

    private fun gateCloseViewId(p: BypassStrategyPolicy): String? =
        gate(BypassExitCandidateType.CLOSE_VIEW_ID, p, 60, 40)

    private fun gateCloseIcon(p: BypassStrategyPolicy): String? =
        gate(BypassExitCandidateType.CLOSE_ICON, p, 40, 40)

    private fun gateCoordinate(p: BypassStrategyPolicy): String? =
        gate(BypassExitCandidateType.COORDINATE_FALLBACK, p, 40, 40)
}
