package app.bypassads.core.actuation

import app.bypassads.core.actuation.ActuationVerdict.Allow
import app.bypassads.core.actuation.ActuationVerdict.Block
import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.DecisionType
import app.bypassads.core.model.IntRect
import app.bypassads.core.model.SkipCandidate
import app.bypassads.core.model.SkipDecision
import app.bypassads.core.model.UiNodeSnapshot
import app.bypassads.core.model.WeightedReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Adversarial fixture corpus for the fail-closed [ActuationGate]
 * (ADR-0002, M2.0 §4 task). Dangerous scenarios must all BLOCK with a
 * concrete reason; the clean positive case must reach ALLOW (the gate is not
 * "always refuse").
 */
class ActuationGateTest {

    private val gate = ActuationGate()
    private val screenWidth = 1080
    private val screenHeight = 2400

    // ---- helpers -----------------------------------------------------------

    private fun candidate(
        label: String,
        bounds: IntRect,
        resourceId: String? = null,
        score: Int = 88,
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
        ancestor: IntRect? = null,
        risks: List<WeightedReason> = emptyList(),
    ) = SkipCandidate(
        features = CandidateFeatures(
            nodeIndex = 0,
            label = label,
            resourceId = resourceId,
            bounds = bounds,
            clickable = clickable,
            enabled = enabled,
            visibleToUser = visible,
            nearestClickableAncestorBounds = ancestor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            stabilityHits = 2,
            countdownStepObserved = true,
        ),
        score = score,
        evidence = emptyList(),
        risks = risks,
    )

    private fun proposal(
        cand: SkipCandidate,
        packageName: String? = "com.demo.app",
        windowCount: Int = 1,
    ) = ActionProposal.fromDecision(
        SkipDecision(DecisionType.WOULD_CLICK, cand, 80),
        packageName,
        windowCount,
        1_000L,
    )!!

    private fun node(
        index: Int,
        parentIndex: Int?,
        resourceId: String?,
        text: String?,
        bounds: IntRect,
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true,
        ancestor: IntRect? = null,
    ) = UiNodeSnapshot(
        index = index,
        parentIndex = parentIndex,
        resourceId = resourceId,
        text = text,
        contentDescription = null,
        className = "android.widget.Button",
        packageName = "com.demo.app",
        bounds = bounds,
        clickable = clickable,
        enabled = enabled,
        visibleToUser = visible,
        depth = 2,
        nearestClickableAncestorBounds = ancestor,
    )

    private fun fresh(
        matches: List<UiNodeSnapshot>,
        packageName: String? = "com.demo.app",
        windowCount: Int = 1,
        cta: Boolean = false,
        countdownAnomaly: Boolean = false,
    ) = FreshTargetResolution(
        packageName = packageName,
        windowCount = windowCount,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        matches = matches,
        ctaSiblingDetected = cta,
        countdownAnomaly = countdownAnomaly,
    )

    private fun topRightSmall(): IntRect = IntRect(900, 60, 1050, 130)

    // ---- positive case ------------------------------------------------------

    @Test
    fun `unique top-right small skip with consistent revalidation is allowed`() {
        val cand = candidate("跳过", topRightSmall(), resourceId = "com.demo:id/skip")
        val prop = proposal(cand)
        val match = node(0, null, "com.demo:id/skip", "跳过", topRightSmall())

        val verdict = gate.evaluate(
            proposal = prop,
            modeAllowsAction = true,
            caseActive = true,
            generationStale = false,
            fresh = fresh(listOf(match)),
            revalidatedScore = 88,
            revalidatedRisks = emptyList(),
        )

        assertIs<ActuationVerdict.Allow>(verdict)
    }

    @Test
    fun `english skip label without resourceId is allowed when unique and consistent`() {
        val cand = candidate("Skip", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "Skip", topRightSmall())

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match)), 88, emptyList())

        assertIs<ActuationVerdict.Allow>(verdict)
    }

    // ---- adversarial corpus: every scenario must BLOCK ----------------------

    @Test
    fun `central cross blocks`() {
        val cand = candidate("×", IntRect(430, 1050, 650, 1130))
        val prop = proposal(cand)
        val match = node(0, null, null, "×", IntRect(430, 1050, 650, 1130))

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.GEOMETRY_CHANGED), verdict)
    }

    @Test
    fun `large close button blocks`() {
        val big = IntRect(100, 800, 980, 1100) // area ratio ~0.24 > 0.06
        val cand = candidate("关闭", big)
        val prop = proposal(cand)
        val match = node(0, null, null, "关闭", big)

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.LARGE_TARGET), verdict)
    }

    @Test
    fun `cta sibling blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match), cta = true), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.CTA_RISK), verdict)
    }

    @Test
    fun `skip with cta present blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match), cta = true), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.CTA_RISK), verdict)
    }

    @Test
    fun `two same-label skips block as multiple targets`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val matches = listOf(
            node(0, null, null, "跳过", topRightSmall()),
            node(1, null, null, "跳过", IntRect(900, 200, 1050, 270)),
        )

        val verdict = gate.evaluate(prop, true, true, false, fresh(matches), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.MULTIPLE_TARGETS), verdict)
    }

    @Test
    fun `two same labels without ids block as multiple targets`() {
        val cand = candidate("跳过", topRightSmall(), resourceId = null)
        val prop = proposal(cand)
        val matches = listOf(
            node(0, null, null, "跳过", topRightSmall()),
            node(1, null, null, "跳过", IntRect(700, 60, 850, 130)),
        )

        val verdict = gate.evaluate(prop, true, true, false, fresh(matches), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.MULTIPLE_TARGETS), verdict)
    }

    @Test
    fun `candidate moved after decision blocks as geometry change`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        // moved far down-left: normalized center distance > 0.05
        val moved = node(0, null, null, "跳过", IntRect(200, 900, 350, 970))

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(moved)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.GEOMETRY_CHANGED), verdict)
    }

    @Test
    fun `candidate disappeared blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)

        val verdict = gate.evaluate(prop, true, true, false, fresh(emptyList()), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.TARGET_DISAPPEARED), verdict)
    }

    @Test
    fun `package switched blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match), packageName = "com.evil.app"), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.PACKAGE_CHANGED), verdict)
    }

    @Test
    fun `window switched blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand, windowCount = 1)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match), windowCount = 2), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.WINDOW_CHANGED), verdict)
    }

    @Test
    fun `large clickable ancestor blocks`() {
        val cand = candidate("跳过", topRightSmall(), ancestor = IntRect(0, 0, 1080, 2400))
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall(), ancestor = IntRect(0, 0, 1080, 2400))

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.ANCESTOR_RISK), verdict)
    }

    @Test
    fun `invisible target blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall(), visible = false)

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.NOT_CLICKABLE), verdict)
    }

    @Test
    fun `disabled target blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall(), enabled = false)

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.NOT_CLICKABLE), verdict)
    }

    @Test
    fun `non-clickable target blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall(), clickable = false)

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.NOT_CLICKABLE), verdict)
    }

    @Test
    fun `revalidated score dropped below threshold blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match)), revalidatedScore = 62, revalidatedRisks = emptyList())

        assertEquals(Block(ActuationBlockReason.SCORE_DROPPED), verdict)
    }

    @Test
    fun `revalidated severe risk blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(
            proposal = prop,
            modeAllowsAction = true,
            caseActive = true,
            generationStale = false,
            fresh = fresh(listOf(match)),
            revalidatedScore = 88,
            revalidatedRisks = listOf(WeightedReason("cta_sibling", -100)),
        )

        assertEquals(Block(ActuationBlockReason.RISK_BLOCKED), verdict)
    }

    @Test
    fun `countdown anomaly blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match), countdownAnomaly = true), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.COUNTDOWN_ANOMALY), verdict)
    }

    @Test
    fun `ended case blocks as stale`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, true, caseActive = false, generationStale = false, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.CASE_STALE), verdict)
    }

    @Test
    fun `off switch after proposal blocks as mode blocked`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, modeAllowsAction = false, caseActive = true, generationStale = false, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.MODE_BLOCKED), verdict)
    }

    @Test
    fun `shadow mode never allows`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, modeAllowsAction = false, caseActive = true, generationStale = false, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.MODE_BLOCKED), verdict)
    }

    @Test
    fun `stale generation from scan busy blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, true, true, generationStale = true, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.STALE_GENERATION), verdict)
    }

    @Test
    fun `resourceId identity mismatch blocks`() {
        val cand = candidate("跳过", topRightSmall(), resourceId = "com.demo:id/skip_a")
        val prop = proposal(cand)
        val match = node(0, null, "com.demo:id/skip_b", "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, true, true, false, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.IDENTITY_AMBIGUOUS), verdict)
    }
}
