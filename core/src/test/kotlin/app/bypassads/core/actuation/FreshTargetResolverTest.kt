package app.bypassads.core.actuation

import app.bypassads.core.model.IntRect
import app.bypassads.core.model.UiNodeSnapshot
import app.bypassads.core.model.UiSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FreshTargetResolver scenarios (M2.1 task 1): unique target resolves, missing
 * / duplicate / changed-label / CTA / countdown / window / snapshot-age facts
 * are all derived from the fresh UiSnapshot or the provided context.
 */
class FreshTargetResolverTest {

    private val resolver = FreshTargetResolver()
    private val fingerprint = TargetFingerprint(
        label = "跳过",
        packageName = "com.demo.app",
        resourceId = null,
        bounds = IntRect(900, 60, 1050, 130),
        screenWidth = 1080,
        screenHeight = 2400,
    )

    private fun node(
        index: Int,
        text: String?,
        bounds: IntRect = IntRect(900, 60, 1050, 130),
        resourceId: String? = null,
        visible: Boolean = true,
    ) = UiNodeSnapshot(
        index = index,
        parentIndex = null,
        resourceId = resourceId,
        text = text,
        contentDescription = null,
        className = "android.widget.Button",
        packageName = "com.demo.app",
        bounds = bounds,
        clickable = true,
        enabled = true,
        visibleToUser = visible,
        depth = 2,
        nearestClickableAncestorBounds = null,
    )

    private fun snapshot(vararg nodes: UiNodeSnapshot, capturedAt: Long = 2_000L) =
        UiSnapshot("com.demo.app", 1080, 2400, capturedAt, 1, nodes.toList())

    private fun resolve(snapshot: UiSnapshot, countdownAnomaly: Boolean = false) =
        resolver.resolve(
            snapshot = snapshot,
            packageName = "com.demo.app",
            caseGeneration = 1L,
            windowContextToken = 10L,
            capturedAtElapsedMs = snapshot.capturedAtElapsedMs,
            fingerprint = fingerprint,
            countdownAnomaly = countdownAnomaly,
        )

    // ---- task card scenarios ------------------------------------------------

    @Test
    fun `unique skip resolves a single match`() {
        val result = resolve(snapshot(node(0, "跳过")))

        assertEquals(1, result.matchCount)
        assertEquals("跳过", result.uniqueMatch?.text)
        assertFalse(result.ctaSiblingDetected)
    }

    @Test
    fun `countdown-qualified skip label still matches sanitized fingerprint`() {
        // "跳过 3" sanitizes to "跳过" — must still resolve (detector does the same).
        val result = resolve(snapshot(node(0, "跳过 3")))

        assertEquals(1, result.matchCount)
    }

    @Test
    fun `contentDescription label resolves`() {
        val node = UiNodeSnapshot(
            index = 0, parentIndex = null, resourceId = null, text = null,
            contentDescription = "跳过", className = "android.widget.ImageButton",
            packageName = "com.demo.app", bounds = IntRect(900, 60, 1050, 130),
            clickable = true, enabled = true, visibleToUser = true, depth = 2,
            nearestClickableAncestorBounds = null,
        )

        val result = resolve(snapshot(node))

        assertEquals(1, result.matchCount)
    }

    @Test
    fun `missing target resolves zero matches`() {
        val result = resolve(snapshot(node(0, "欢迎")))

        assertEquals(0, result.matchCount)
    }

    @Test
    fun `label changed on revalidation resolves zero matches`() {
        // Proposal label "跳过"; fresh tree now says "关闭".
        val result = resolve(snapshot(node(0, "关闭")))

        assertEquals(0, result.matchCount)
    }

    @Test
    fun `two skip labels resolve two matches`() {
        val result = resolve(snapshot(node(0, "跳过"), node(1, "跳过")))

        assertEquals(2, result.matchCount)
    }

    @Test
    fun `resource identity change is visible in the matched node`() {
        val result = resolve(snapshot(node(0, "跳过", resourceId = "com.demo:id/skip_b")))

        assertEquals(1, result.matchCount)
        assertEquals("com.demo:id/skip_b", result.uniqueMatch?.resourceId)
    }

    @Test
    fun `cta sibling presence is derived from the fresh tree`() {
        val result = resolve(snapshot(node(0, "跳过"), node(1, "立即体验")))

        assertTrue(result.ctaSiblingDetected)
    }

    @Test
    fun `non-cta sibling does not raise cta risk`() {
        val result = resolve(snapshot(node(0, "跳过"), node(1, "说明文档")))

        assertFalse(result.ctaSiblingDetected)
    }

    @Test
    fun `countdown anomaly signal is carried into the resolution`() {
        val result = resolve(snapshot(node(0, "跳过")), countdownAnomaly = true)

        assertTrue(result.countdownAnomaly)
    }

    @Test
    fun `window token and generation are carried into the resolution`() {
        val result = resolver.resolve(
            snapshot = snapshot(node(0, "跳过")),
            packageName = "com.demo.app",
            caseGeneration = 7L,
            windowContextToken = 42L,
            capturedAtElapsedMs = 5_000L,
            fingerprint = fingerprint,
        )

        assertEquals(7L, result.currentCaseGeneration)
        assertEquals(42L, result.currentWindowContextToken)
        assertEquals(5_000L, result.freshCapturedAtElapsedMs)
    }

    @Test
    fun `capture timestamp comes from the fresh snapshot`() {
        val result = resolve(snapshot(node(0, "跳过"), capturedAt = 3_333L))

        assertEquals(3_333L, result.freshCapturedAtElapsedMs)
    }

    // ---- full pipeline: resolver -> gate ------------------------------------

    @Test
    fun `unique target reaches gate allow`() {
        val proposal = ActionProposal.fromDecision(
            app.bypassads.core.model.SkipDecision(
                app.bypassads.core.model.DecisionType.WOULD_CLICK,
                candidate("跳过"),
                88,
            ),
            "com.demo.app",
            1L,
            10L,
            1_000L,
        )!!
        val fresh = resolve(snapshot(node(0, "跳过")))

        val verdict = ActuationGate().evaluate(
            proposal = proposal,
            modeAllowsAction = true,
            caseActive = true,
            attemptsAlreadyMade = 0,
            fresh = fresh,
            revalidatedScore = 88,
            revalidatedRisks = emptyList(),
        )

        assertIsAllow(verdict)
    }

    @Test
    fun `missing target blocks at the gate`() {
        val proposal = ActionProposal.fromDecision(
            app.bypassads.core.model.SkipDecision(
                app.bypassads.core.model.DecisionType.WOULD_CLICK,
                candidate("跳过"),
                88,
            ),
            "com.demo.app",
            1L,
            10L,
            1_000L,
        )!!
        val fresh = resolve(snapshot(node(0, "欢迎")))

        val verdict = ActuationGate().evaluate(
            proposal = proposal,
            modeAllowsAction = true,
            caseActive = true,
            attemptsAlreadyMade = 0,
            fresh = fresh,
            revalidatedScore = 88,
            revalidatedRisks = emptyList(),
        )

        assertEquals(
            ActuationVerdict.Block(ActuationBlockReason.TARGET_DISAPPEARED),
            verdict,
        )
    }

    @Test
    fun `duplicate target blocks at the gate`() {
        val proposal = ActionProposal.fromDecision(
            app.bypassads.core.model.SkipDecision(
                app.bypassads.core.model.DecisionType.WOULD_CLICK,
                candidate("跳过"),
                88,
            ),
            "com.demo.app",
            1L,
            10L,
            1_000L,
        )!!
        val fresh = resolve(snapshot(node(0, "跳过"), node(1, "跳过")))

        val verdict = ActuationGate().evaluate(
            proposal = proposal,
            modeAllowsAction = true,
            caseActive = true,
            attemptsAlreadyMade = 0,
            fresh = fresh,
            revalidatedScore = 88,
            revalidatedRisks = emptyList(),
        )

        assertEquals(
            ActuationVerdict.Block(ActuationBlockReason.MULTIPLE_TARGETS),
            verdict,
        )
    }

    @Test
    fun `cta risk from resolver blocks at the gate`() {
        val proposal = ActionProposal.fromDecision(
            app.bypassads.core.model.SkipDecision(
                app.bypassads.core.model.DecisionType.WOULD_CLICK,
                candidate("跳过"),
                88,
            ),
            "com.demo.app",
            1L,
            10L,
            1_000L,
        )!!
        val fresh = resolve(snapshot(node(0, "跳过"), node(1, "立即体验")))

        val verdict = ActuationGate().evaluate(
            proposal = proposal,
            modeAllowsAction = true,
            caseActive = true,
            attemptsAlreadyMade = 0,
            fresh = fresh,
            revalidatedScore = 88,
            revalidatedRisks = emptyList(),
        )

        assertEquals(
            ActuationVerdict.Block(ActuationBlockReason.CTA_RISK),
            verdict,
        )
    }

    private fun candidate(label: String) = app.bypassads.core.model.SkipCandidate(
        features = app.bypassads.core.model.CandidateFeatures(
            nodeIndex = 0,
            label = label,
            resourceId = null,
            bounds = IntRect(900, 60, 1050, 130),
            clickable = true,
            enabled = true,
            visibleToUser = true,
            nearestClickableAncestorBounds = null,
            screenWidth = 1080,
            screenHeight = 2400,
            stabilityHits = 2,
            countdownStepObserved = true,
        ),
        score = 88,
        evidence = emptyList(),
        risks = emptyList(),
    )

    private fun assertIsAllow(verdict: ActuationVerdict) {
        assertTrue(verdict is ActuationVerdict.Allow, "expected Allow but was $verdict")
    }
}
