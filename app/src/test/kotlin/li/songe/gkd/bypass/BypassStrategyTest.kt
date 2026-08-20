package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the three-tier strategy engine.
 *
 * Only pure Kotlin logic is covered here (classifier, policy mapping,
 * strategy-level marker parsing). Android node plumbing is covered by the
 * instrumented matrix on device.
 */
class BypassStrategyTest {

    // ------------------------------------------------------------------
    // Policy mapping (task 54-55)
    // ------------------------------------------------------------------

    @Test
    fun conservative_policy_gates_everything_risky() {
        val p = BypassAdStrategyMode.CONSERVATIVE.policy
        assertFalse(p.allowGenericCloseText)
        assertFalse(p.allowCloseViewId)
        assertFalse(p.allowStructuralCloseIcon)
        assertFalse(p.allowCoordinateFallback)
        assertEquals(1, p.maxExitAttempts)
    }

    @Test
    fun aggressive_policy_allows_close_but_not_coordinate() {
        val p = BypassAdStrategyMode.AGGRESSIVE.policy
        assertTrue(p.allowGenericCloseText)
        assertTrue(p.allowCloseViewId)
        assertTrue(p.allowStructuralCloseIcon)
        assertFalse(p.allowCoordinateFallback)
        assertEquals(2, p.maxExitAttempts)
    }

    @Test
    fun crazy_policy_allows_everything_with_bounded_attempts() {
        val p = BypassAdStrategyMode.CRAZY.policy
        assertTrue(p.allowGenericCloseText)
        assertTrue(p.allowCloseViewId)
        assertTrue(p.allowStructuralCloseIcon)
        assertTrue(p.allowCoordinateFallback)
        assertEquals(3, p.maxExitAttempts)
    }

    @Test
    fun default_mode_is_conservative() {
        assertEquals(BypassAdStrategyMode.CONSERVATIVE, BypassAdStrategyMode.from(0))
        assertEquals(BypassAdStrategyMode.AGGRESSIVE, BypassAdStrategyMode.from(1))
        assertEquals(BypassAdStrategyMode.CRAZY, BypassAdStrategyMode.from(2))
        // out-of-range falls back to conservative
        assertEquals(BypassAdStrategyMode.CONSERVATIVE, BypassAdStrategyMode.from(99))
    }

    @Test
    fun labels_are_stable() {
        assertEquals("保守", BypassAdStrategyMode.CONSERVATIVE.label)
        assertEquals("激进", BypassAdStrategyMode.AGGRESSIVE.label)
        assertEquals("彻底疯狂", BypassAdStrategyMode.CRAZY.label)
    }

    // ------------------------------------------------------------------
    // Candidate classifier (task 115-118)
    // ------------------------------------------------------------------

    @Test
    fun skip_text_is_classified_skip() {
        assertEquals(
            BypassExitCandidateType.SKIP_TEXT,
            BypassExitClassifier.classify("跳过", null, null, "android.widget.TextView", true, 80, 40),
        )
        assertEquals(
            BypassExitCandidateType.SKIP_TEXT,
            BypassExitClassifier.classify("跳過", null, null, "android.widget.TextView", true, 80, 40),
        )
        assertEquals(
            BypassExitCandidateType.SKIP_TEXT,
            BypassExitClassifier.classify(null, "跳过", null, "android.widget.TextView", true, 80, 40),
        )
    }

    @Test
    fun close_text_is_classified_close_text() {
        assertEquals(
            BypassExitCandidateType.CLOSE_TEXT,
            BypassExitClassifier.classify("关闭", null, null, "android.widget.TextView", true, 80, 40),
        )
        assertEquals(
            BypassExitCandidateType.CLOSE_TEXT,
            BypassExitClassifier.classify("关闭广告", null, null, "android.widget.TextView", true, 120, 60),
        )
        assertEquals(
            BypassExitCandidateType.CLOSE_TEXT,
            BypassExitClassifier.classify("Close", null, null, "android.widget.TextView", true, 80, 40),
        )
    }

    @Test
    fun close_desc_is_classified_close_desc() {
        assertEquals(
            BypassExitCandidateType.CLOSE_DESC,
            BypassExitClassifier.classify(null, "关闭", null, "android.widget.ImageView", true, 60, 60),
        )
        assertEquals(
            BypassExitCandidateType.CLOSE_DESC,
            BypassExitClassifier.classify(null, "close", null, "android.widget.ImageView", true, 60, 60),
        )
    }

    @Test
    fun ad_close_view_id_is_classified_view_id() {
        assertEquals(
            BypassExitCandidateType.CLOSE_VIEW_ID,
            BypassExitClassifier.classify(null, null, "com.example:id/ad_close", "android.widget.ImageView", true, 60, 60),
        )
        assertEquals(
            BypassExitCandidateType.CLOSE_VIEW_ID,
            BypassExitClassifier.classify(null, null, "com.example:id/splash_close_btn", "android.widget.TextView", true, 60, 40),
        )
    }

    @Test
    fun x_glyph_is_classified_close_icon() {
        assertEquals(
            BypassExitCandidateType.CLOSE_ICON,
            BypassExitClassifier.classify("X", null, null, "android.widget.TextView", true, 40, 40),
        )
        assertEquals(
            BypassExitCandidateType.CLOSE_ICON,
            BypassExitClassifier.classify("×", null, null, "android.widget.TextView", true, 40, 40),
        )
    }

    @Test
    fun small_image_is_structural_close() {
        assertEquals(
            BypassExitCandidateType.STRUCTURAL_CLOSE,
            BypassExitClassifier.classify(null, null, null, "android.widget.ImageView", false, 60, 60),
        )
    }

    // ------------------------------------------------------------------
    // Negative semantics are hard gates (task 62, 118)
    // ------------------------------------------------------------------

    @Test
    fun next_and_skip_intro_are_never_exit_candidates() {
        assertNull(BypassExitClassifier.classify("NEXT", null, null, "android.widget.TextView", true, 100, 50))
        assertNull(BypassExitClassifier.classify("跳过片头", null, null, "android.widget.TextView", true, 200, 60))
        assertNull(BypassExitClassifier.classify("跳过片尾", null, null, "android.widget.TextView", true, 200, 60))
        assertNull(BypassExitClassifier.classify("取消", null, null, "android.widget.TextView", true, 80, 40))
        assertNull(BypassExitClassifier.classify("下一步", null, null, "android.widget.TextView", true, 100, 50))
    }

    @Test
    fun sensitive_semantics_are_never_exit_candidates() {
        assertNull(BypassExitClassifier.classify("确认支付", null, null, "android.widget.TextView", true, 300, 80))
        assertNull(BypassExitClassifier.classify("允许", null, null, "android.widget.TextView", true, 100, 50))
        assertNull(BypassExitClassifier.classify("提交订单", null, null, "android.widget.TextView", true, 200, 60))
        assertNull(BypassExitClassifier.classify(null, "授权登录", null, "android.widget.TextView", true, 100, 50))
    }

    // ------------------------------------------------------------------
    // High-risk app exclusion (task 11)
    // ------------------------------------------------------------------

    @Test
    fun high_risk_apps_are_excluded_from_generic_fallback() {
        assertTrue(isBypassHighRiskApp("com.eg.android.AlipayGphone"))
        assertTrue(isBypassHighRiskApp("com.android.settings"))
        assertTrue(isBypassHighRiskApp("com.tencent.mm"))
        assertTrue(isBypassHighRiskApp("com.unionpay"))
        assertFalse(isBypassHighRiskApp("com.example.normal.app"))
    }

    @Test
    fun strategy_level_markers_parse_correctly() {
        assertEquals(0, bypassRequiredStrategyLevel("Bypass-WeChat-Skip"))
        assertEquals(1, bypassRequiredStrategyLevel("Bypass-WeChat-Close-Aggressive"))
        assertEquals(2, bypassRequiredStrategyLevel("Bypass-X-Crazy"))
        assertEquals(0, bypassRequiredStrategyLevel(null))
    }
}
