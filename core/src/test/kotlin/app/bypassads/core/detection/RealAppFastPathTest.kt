package app.bypassads.core.detection

import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.IntRect
import app.bypassads.core.model.UiSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-App Fast Path rule matching (M2.3 Real-App Pilot).
 *
 * A rule fires only when package + sanitized label + top-right region +
 * stability + area bound + splash fingerprint (class + center zone) all
 * match, and never on ambiguous/CTA/drifting targets. The produced candidate
 * must carry the exact fastPathRuleId and a score above the click threshold
 * so it can ride the normal decision pipeline.
 */
class RealAppFastPathTest {

    private val rules = listOf(
        FastPathRule(
            ruleId = "weibo_splash_skip_v1",
            packageName = "com.sina.weibo",
            label = "跳过",
            minStabilityHits = 1,
            anchorClassName = "android.widget.TextView",
            minCenterXRatio = 0.80,
            maxCenterYRatio = 0.15,
        ),
        FastPathRule(
            ruleId = "zhihu_splash_skip_v1",
            packageName = "com.zhihu.android",
            label = "跳过",
            minStabilityHits = 1,
            anchorClassName = "android.widget.TextView",
            anchorResourceId = "com.zhihu.android:id/btn_skip",
            minCenterXRatio = 0.80,
            maxCenterYRatio = 0.15,
        ),
        FastPathRule(
            ruleId = "qqmusic_splash_skip_v1",
            packageName = "com.tencent.qqmusic",
            label = "跳过",
            minStabilityHits = 1,
            anchorClassName = "android.widget.TextView",
            minCenterXRatio = 0.80,
            maxCenterYRatio = 0.15,
        ),
    )
    private val fastPath = RealAppFastPath(rules)

    private val screenWidth = 1200
    private val screenHeight = 2416

    /** Real observed weibo splash skip: TextView at [984,215,1158,311]. */
    private fun feature(
        nodeIndex: Int = 0,
        label: String = "跳过",
        bounds: IntRect = IntRect(984, 215, 1158, 311),
        className: String? = "android.widget.TextView",
        resourceId: String? = null,
        clickable: Boolean = false,
        stabilityHits: Int = 6,
        cta: Boolean = false,
        ambiguous: Boolean = false,
        drift: Boolean = false,
    ) = CandidateFeatures(
        nodeIndex = nodeIndex,
        label = label,
        resourceId = resourceId,
        bounds = bounds,
        clickable = clickable,
        enabled = true,
        visibleToUser = true,
        nearestClickableAncestorBounds = null,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        stabilityHits = stabilityHits,
        ctaSiblingDetected = cta,
        identityAmbiguous = ambiguous,
        positionDriftDetected = drift,
        className = className,
    )

    private fun snapshot(packageName: String?) = UiSnapshot(packageName, screenWidth, screenHeight, 2_000L, 1, emptyList())

    @Test
    fun `matching weibo splash skip produces a fast path candidate with rule id`() {
        val result = fastPath.score(snapshot("com.sina.weibo"), listOf(feature()))

        assertEquals(1, result.size)
        val cand = result[0]
        assertEquals("weibo_splash_skip_v1", cand.fastPathRuleId)
        assertTrue(cand.score >= 80, "fast path score must clear the click threshold, was ${cand.score}")
        assertEquals("跳过", cand.label)
    }

    @Test
    fun `non-matching package produces nothing`() {
        val result = fastPath.score(snapshot("com.tencent.mm"), listOf(feature()))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `wrong label produces nothing`() {
        val result = fastPath.score(snapshot("com.sina.weibo"), listOf(feature(label = "立即体验")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `single-frame skip still fires when rule allows first frame`() {
        // Field observation: the weibo ad frame can be present for a single
        // burst frame only, so the rule must fire with stabilityHits=1.
        val singleFrame = feature(stabilityHits = 1)
        val result = fastPath.score(snapshot("com.sina.weibo"), listOf(singleFrame))

        assertEquals(1, result.size)
        assertEquals("weibo_splash_skip_v1", result[0].fastPathRuleId)
    }

    @Test
    fun `non top-right target produces nothing`() {
        val centered = feature(bounds = IntRect(400, 1000, 600, 1100))
        val result = fastPath.score(snapshot("com.sina.weibo"), listOf(centered))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `oversized anchor produces nothing (small_target evidence is real)`() {
        val oversized = feature(bounds = IntRect(500, 100, 1150, 900)) // area ratio ~0.19 > 0.06
        val result = fastPath.score(snapshot("com.sina.weibo"), listOf(oversized))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ambiguous or CTA or drifting target never fires`() {
        assertTrue(fastPath.score(snapshot("com.sina.weibo"), listOf(feature(cta = true))).isEmpty())
        assertTrue(fastPath.score(snapshot("com.sina.weibo"), listOf(feature(ambiguous = true))).isEmpty())
        assertTrue(fastPath.score(snapshot("com.sina.weibo"), listOf(feature(drift = true))).isEmpty())
    }

    @Test
    fun `same position but different class does not fire (splash fingerprint)`() {
        // e.g. an in-app "跳过" drawn as a Button instead of the splash TextView.
        val button = feature(className = "android.widget.Button")
        val result = fastPath.score(snapshot("com.sina.weibo"), listOf(button))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `same class but off-fingerprint position does not fire (splash fingerprint)`() {
        // e.g. a right-edge "跳过" lower on the page — not the observed splash zone.
        val lower = feature(bounds = IntRect(984, 400, 1158, 496)) // center y ~0.19 > 0.15
        val result = fastPath.score(snapshot("com.sina.weibo"), listOf(lower))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `zhihu skip with stable resource id fires its own rule`() {
        val zhihu = feature(
            bounds = IntRect(998, 123, 1155, 199),
            resourceId = "com.zhihu.android:id/btn_skip",
            clickable = true,
            stabilityHits = 1,
        )
        val result = fastPath.score(snapshot("com.zhihu.android"), listOf(zhihu))

        assertEquals(1, result.size)
        assertEquals("zhihu_splash_skip_v1", result[0].fastPathRuleId)
    }

    @Test
    fun `zhihu skip with wrong resource id does not fire`() {
        val zhihu = feature(
            bounds = IntRect(998, 123, 1155, 199),
            resourceId = "com.zhihu.android:id/other",
            stabilityHits = 1,
        )
        val result = fastPath.score(snapshot("com.zhihu.android"), listOf(zhihu))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `qq music skip with clickable anchor fires its own rule`() {
        val qq = feature(
            bounds = IntRect(1005, 174, 1155, 249),
            clickable = true,
            stabilityHits = 1,
        )
        val result = fastPath.score(snapshot("com.tencent.qqmusic"), listOf(qq))

        assertEquals(1, result.size)
        assertEquals("qqmusic_splash_skip_v1", result[0].fastPathRuleId)
    }

    @Test
    fun `label matches sanitized rule label`() {
        val result = fastPath.score(snapshot("com.sina.weibo"), listOf(feature()))

        assertEquals(1, result.size)
    }
}
