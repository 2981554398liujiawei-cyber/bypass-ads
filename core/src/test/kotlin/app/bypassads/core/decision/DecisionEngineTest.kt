package app.bypassads.core.decision

import app.bypassads.core.detection.CandidateDetector
import app.bypassads.core.detection.TemporalCandidateTracker
import app.bypassads.core.model.DecisionType
import app.bypassads.core.model.IntRect
import app.bypassads.core.model.UiNodeSnapshot
import app.bypassads.core.model.UiSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecisionEngineTest {
    private val detector = CandidateDetector()
    private val engine = DecisionEngine()

    @Test
    fun `precise top-right skip node is accepted only after stable frames`() {
        val tracker = TemporalCandidateTracker()
        val frame = detector.extract(
            snapshot(node(0, null, "com.demo:id/ad_skip", "跳过 4", IntRect(900, 60, 1050, 130), true)),
        )

        tracker.enrich(frame)
        val decision = engine.decide(detector.score(tracker.enrich(frame)))

        assertEquals(DecisionType.WOULD_CLICK, decision.type)
        assertTrue(decision.candidate!!.score >= 80)
    }

    @Test
    fun `central close-like text is not trusted`() {
        val decision = engine.decide(
            detector.detect(snapshot(node(0, null, null, "关闭", IntRect(430, 1050, 650, 1130), true))),
        )

        assertEquals(DecisionType.TOO_RISKY, decision.type)
    }

    @Test
    fun `single ambiguous cross is rejected`() {
        val decision = engine.decide(
            detector.detect(snapshot(node(0, null, null, "✕", IntRect(960, 70, 1030, 140), true))),
        )

        assertEquals(DecisionType.TOO_RISKY, decision.type)
        assertTrue(decision.candidate!!.risks.any { it.code == "ambiguous_cross" })
    }

    @Test
    fun `large clickable ancestor remains unsafe after stable frames`() {
        val tracker = TemporalCandidateTracker()
        val frame = detector.extract(
            snapshot(node(0, 1, null, "跳过", IntRect(960, 70, 1030, 140), true, IntRect(0, 0, 1080, 2400))),
        )

        tracker.enrich(frame)
        val decision = engine.decide(detector.score(tracker.enrich(frame)))

        assertEquals(DecisionType.TOO_RISKY, decision.type)
        assertTrue(decision.candidate!!.risks.any { it.code == "large_clickable_ancestor" })
    }

    @Test
    fun `skip label next to CTA sibling remains unsafe after stable frames`() {
        val parent = node(0, null, null, null, IntRect(850, 30, 1080, 260), false)
        val skip = node(1, 0, "com.demo:id/ad_skip", "跳过", IntRect(920, 60, 1050, 130), true)
        val cta = node(2, 0, null, "立即领取", IntRect(880, 150, 1050, 220), true)
        val tracker = TemporalCandidateTracker()
        val frame = detector.extract(snapshot(parent, skip, cta))

        tracker.enrich(frame)
        val candidate = detector.score(tracker.enrich(frame)).single()

        assertTrue(candidate.risks.any { it.code == "cta_sibling" })
        assertEquals(DecisionType.TOO_RISKY, engine.decide(listOf(candidate)).type)
    }

    @Test
    fun `spatially separated trusted candidates are rejected as a conflict`() {
        val right = node(0, null, "com.demo:id/ad_skip", "跳过", IntRect(900, 60, 1050, 130), true)
        val otherRight = node(1, null, "com.demo:id/skip_overlay", "跳过", IntRect(700, 60, 850, 130), true)
        val tracker = TemporalCandidateTracker()
        val frame = detector.extract(snapshot(right, otherRight))

        tracker.enrich(frame)
        val decision = engine.decide(detector.score(tracker.enrich(frame)))

        assertEquals(DecisionType.TOO_RISKY, decision.type)
        assertEquals("conflicting_candidates", decision.rejectionReason)
        assertTrue(decision.candidate!!.risks.any { it.code == "competing_candidate" })
    }

    @Test
    fun `countdown progression across 5 4 3 adds temporal evidence`() {
        val tracker = TemporalCandidateTracker()
        val first = detector.extract(snapshot(node(0, null, null, "跳过 5", IntRect(920, 60, 1050, 130), true)))
        val second = detector.extract(snapshot(node(0, null, null, "跳过 4", IntRect(920, 60, 1050, 130), true)))
        val third = detector.extract(snapshot(node(0, null, null, "跳过 3", IntRect(920, 60, 1050, 130), true)))

        tracker.enrich(first)
        tracker.enrich(second)
        val enriched = tracker.enrich(third).single()

        assertEquals("跳过", enriched.label)
        assertEquals(3, enriched.stabilityHits)
        assertEquals(3, enriched.countdownValue)
        assertTrue(enriched.countdownStepObserved)
    }

    @Test
    fun `small cross-frame movement remains stable`() {
        val tracker = TemporalCandidateTracker()
        val first = detector.extract(
            snapshot(node(0, null, "com.demo:id/ad_skip", "跳过", IntRect(900, 60, 1050, 130), true)),
        )
        val second = detector.extract(
            snapshot(node(0, null, "com.demo:id/ad_skip", "跳过", IntRect(910, 66, 1060, 136), true)),
        )

        tracker.enrich(first)
        val enriched = tracker.enrich(second).single()

        assertEquals(2, enriched.stabilityHits)
        assertFalse(enriched.positionDriftDetected)
    }

    @Test
    fun `rapid candidate movement is rejected`() {
        val tracker = TemporalCandidateTracker()
        val first = detector.extract(
            snapshot(node(0, null, "com.demo:id/ad_skip", "跳过", IntRect(900, 60, 1050, 130), true)),
        )
        val second = detector.extract(
            snapshot(node(0, null, "com.demo:id/ad_skip", "跳过", IntRect(280, 760, 430, 830), true)),
        )

        val third = detector.extract(
            snapshot(node(0, null, "com.demo:id/ad_skip", "跳过", IntRect(286, 766, 436, 836), true)),
        )

        tracker.enrich(first)
        val drifted = tracker.enrich(second).single()
        val candidate = detector.score(tracker.enrich(third)).single()
        val decision = engine.decide(listOf(candidate))

        assertTrue(drifted.positionDriftDetected)
        assertTrue(candidate.risks.any { it.code == "rapid_position_drift" })
        assertEquals(DecisionType.TOO_RISKY, decision.type)
        assertEquals("severe_risk", decision.rejectionReason)
    }

    @Test
    fun `candidate disappearance does not preserve stability`() {
        val tracker = TemporalCandidateTracker()
        val candidateFrame = detector.extract(
            snapshot(node(0, null, "com.demo:id/ad_skip", "跳过", IntRect(900, 60, 1050, 130), true)),
        )

        assertEquals(1, tracker.enrich(candidateFrame).single().stabilityHits)
        assertTrue(tracker.enrich(emptyList()).isEmpty())
        val reappeared = tracker.enrich(candidateFrame).single()

        assertEquals(1, reappeared.stabilityHits)
        assertFalse(reappeared.positionDriftDetected)
    }

    @Test
    fun `candidate appearing on a later frame is not treated as drift`() {
        val tracker = TemporalCandidateTracker()
        val emptyFrame = detector.extract(
            UiSnapshot("com.demo", 1080, 2400, 1L, 0, emptyList()),
        )
        val candidateFrame = detector.extract(
            snapshot(node(0, null, "com.demo:id/ad_skip", "跳过", IntRect(900, 60, 1050, 130), true)),
        )

        assertTrue(tracker.enrich(emptyFrame).isEmpty())
        val appeared = tracker.enrich(candidateFrame).single()

        assertEquals(1, appeared.stabilityHits)
        assertFalse(appeared.positionDriftDetected)
        assertEquals(DecisionType.TOO_RISKY, engine.decide(detector.score(listOf(appeared))).type)
    }

    @Test
    fun `same-label candidates are ambiguous rather than falsely drifting`() {
        val tracker = TemporalCandidateTracker()
        val first = detector.extract(
            snapshot(
                node(0, null, null, "跳过", IntRect(900, 60, 1050, 130), true),
                node(1, null, null, "跳过", IntRect(30, 60, 180, 130), true),
            ),
        )
        val second = detector.extract(
            snapshot(
                node(0, null, null, "跳过", IntRect(900, 60, 1050, 130), true),
                node(1, null, null, "跳过", IntRect(30, 60, 180, 130), true),
            ),
        )

        tracker.enrich(first)
        val enriched = tracker.enrich(second)

        assertTrue(enriched.all { it.stabilityHits == 1 })
        assertTrue(enriched.none { it.positionDriftDetected })
        assertTrue(enriched.all { it.identityAmbiguous })
        assertTrue(enriched.all { feature ->
            engine.decide(detector.score(listOf(feature))).type == DecisionType.TOO_RISKY
        })
    }

    @Test
    fun `resource id without an explicit text signal is not a candidate`() {
        val decision = engine.decide(
            detector.detect(snapshot(node(0, null, "com.demo:id/skip_onboarding", null, IntRect(900, 60, 1050, 130), true))),
        )

        assertEquals(DecisionType.NO_CANDIDATE, decision.type)
    }

    @Test
    fun `node without a matching package is ignored`() {
        val unknownPackageNode = node(
            index = 0,
            parentIndex = null,
            id = "com.demo:id/ad_skip",
            text = "跳过",
            bounds = IntRect(900, 60, 1050, 130),
            clickable = true,
            packageName = null,
        )

        assertEquals(DecisionType.NO_CANDIDATE, engine.decide(detector.detect(snapshot(unknownPackageNode))).type)
    }

    @Test
    fun `empty root or window produces no candidate`() {
        val emptySnapshot = UiSnapshot(
            packageName = "com.demo",
            screenWidth = 1080,
            screenHeight = 2400,
            capturedAtElapsedMs = 1L,
            windowCount = 0,
            nodes = emptyList(),
        )

        assertEquals(DecisionType.NO_CANDIDATE, engine.decide(detector.detect(emptySnapshot)).type)
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
        packageName: String? = "com.demo",
    ) = UiNodeSnapshot(
        index = index,
        parentIndex = parentIndex,
        resourceId = id,
        text = text,
        contentDescription = null,
        className = "android.widget.TextView",
        packageName = packageName,
        bounds = bounds,
        clickable = clickable,
        enabled = true,
        visibleToUser = true,
        depth = if (parentIndex == null) 0 else 1,
        nearestClickableAncestorBounds = ancestor,
    )
}
