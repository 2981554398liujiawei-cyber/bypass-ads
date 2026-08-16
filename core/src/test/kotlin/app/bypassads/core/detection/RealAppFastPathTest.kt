package app.bypassads.core.detection

import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.IntRect
import app.bypassads.core.model.UiSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-App Fast Path rule matching (M2.3 Real-App Pilot).
 *
 * A rule fires only when package + sanitized label + top-right region +
 * stability + area bound all match, and never on ambiguous/CTA/drifting
 * targets. The produced candidate must carry the exact fastPathRuleId and a
 * score above the click threshold so it can ride the normal decision pipeline.
 */
class RealAppFastPathTest {

    private val rules = listOf(
        FastPathRule(ruleId = "weibo_splash_skip_v1", packageName = "com.sina.weibo", label = "跳过", minStabilityHits = 2),
    )
    private val fastPath = RealAppFastPath(rules)

    private val screenWidth = 1200
    private val screenHeight = 2416

    private fun feature(
        nodeIndex: Int = 0,
        label: String = "跳过",
        bounds: IntRect = IntRect(984, 215, 1158, 311),
        clickable: Boolean = false,
        stabilityHits: Int = 6,
        cta: Boolean = false,
        ambiguous: Boolean = false,
        drift: Boolean = false,
    ) = CandidateFeatures(
        nodeIndex = nodeIndex,
        label = label,
        resourceId = null,
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
    )

    private fun snapshot(packageName: String?) = UiSnapshot(packageName, screenWidth, screenHeight, 2_000L, 1, emptyList())

    @Test
    fun `matching weibo skip produces a fast path candidate with rule id`() {
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
    fun `insufficient stability produces nothing`() {
        val result = fastPath.score(snapshot("com.sina.weibo"), listOf(feature(stabilityHits = 1)))

        assertTrue(result.isEmpty())
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
    fun `label matches sanitized rule label`() {
        // features.label is already sanitized by the detector pipeline; the
        // rule label is sanitized the same way, so both sides agree.
        val result = fastPath.score(snapshot("com.sina.weibo"), listOf(feature()))

        assertEquals(1, result.size)
    }
}
