package app.bypassads.core.decision

import app.bypassads.core.detection.CandidateDetector
import app.bypassads.core.detection.TemporalCandidateTracker
import app.bypassads.core.model.DecisionType
import app.bypassads.core.model.IntRect
import app.bypassads.core.model.UiNodeSnapshot
import app.bypassads.core.model.UiSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DecisionEngineTest {
    private val detector = CandidateDetector()
    private val engine = DecisionEngine()

    @Test
    fun `precise top-right skip node is accepted`() {
        val decision = engine.decide(detector.detect(snapshot(node(0, null, "com.demo:id/ad_skip", "跳过 4", IntRect(900, 60, 1050, 130), true))))
        assertEquals(DecisionType.WOULD_CLICK, decision.type)
        assertTrue(decision.candidate!!.score >= 80)
    }

    @Test
    fun `fake cross backed by full-screen clickable ancestor is rejected`() {
        val decision = engine.decide(detector.detect(snapshot(node(0, 1, null, "✕", IntRect(960, 70, 1030, 140), false, IntRect(0, 0, 1080, 2400)))))
        assertEquals(DecisionType.TOO_RISKY, decision.type)
        assertTrue(decision.candidate!!.risks.any { it.code == "large_clickable_ancestor" })
    }

    @Test
    fun `central close-like text is not trusted`() {
        val decision = engine.decide(detector.detect(snapshot(node(0, null, null, "关闭", IntRect(430, 1050, 650, 1130), true))))
        assertEquals(DecisionType.TOO_RISKY, decision.type)
    }

    @Test
    fun `skip label next to CTA sibling is penalized`() {
        val parent = node(0, null, null, null, IntRect(850, 30, 1080, 260), false)
        val skip = node(1, 0, null, "跳过", IntRect(920, 60, 1050, 130), true)
        val cta = node(2, 0, null, "立即领取", IntRect(880, 150, 1050, 220), true)
        val candidate = detector.detect(snapshot(parent, skip, cta)).first()
        assertTrue(candidate.risks.any { it.code == "cta_sibling" })
        assertEquals(DecisionType.TOO_RISKY, engine.decide(listOf(candidate)).type)
    }

    @Test
    fun `countdown progression adds temporal evidence without storing raw text`() {
        val tracker = TemporalCandidateTracker()
        val first = detector.extract(snapshot(node(0, null, null, "跳过 5", IntRect(920, 60, 1050, 130), true)))
        val second = detector.extract(snapshot(node(0, null, null, "跳过 4", IntRect(920, 60, 1050, 130), true)))
        tracker.enrich(first)
        val enriched = tracker.enrich(second).single()
        assertEquals("跳过", enriched.label)
        assertEquals(2, enriched.stabilityHits)
        assertTrue(enriched.countdownStepObserved)
    }

    private fun snapshot(vararg nodes: UiNodeSnapshot) = UiSnapshot("com.demo", 1080, 2400, 1L, 1, nodes.toList())

    private fun node(
        index: Int,
        parentIndex: Int?,
        id: String?,
        text: String?,
        bounds: IntRect,
        clickable: Boolean,
        ancestor: IntRect? = null,
    ) = UiNodeSnapshot(
        index = index,
        parentIndex = parentIndex,
        resourceId = id,
        text = text,
        contentDescription = null,
        className = "android.widget.TextView",
        packageName = "com.demo",
        bounds = bounds,
        clickable = clickable,
        enabled = true,
        visibleToUser = true,
        depth = if (parentIndex == null) 0 else 1,
        nearestClickableAncestorBounds = ancestor,
    )
}
