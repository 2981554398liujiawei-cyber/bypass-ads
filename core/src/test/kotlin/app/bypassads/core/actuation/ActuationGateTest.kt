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
 * concrete reason; the clean positive cases must reach ALLOW (the gate is not
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
        generation: Long = 1L,
        windowToken: Long = 10L,
        elapsedMs: Long = 1_000L,
    ) = ActionProposal.fromDecision(
        SkipDecision(DecisionType.WOULD_CLICK, cand, 80),
        packageName,
        generation,
        windowToken,
        elapsedMs,
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
        generation: Long = 1L,
        windowToken: Long = 10L,
        capturedAtMs: Long = 2_000L,
        cta: Boolean = false,
        countdownAnomaly: Boolean = false,
    ) = FreshTargetResolution(
        packageName = packageName,
        currentCaseGeneration = generation,
        currentWindowContextToken = windowToken,
        freshCapturedAtElapsedMs = capturedAtMs,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        matches = matches,
        ctaSiblingDetected = cta,
        countdownAnomaly = countdownAnomaly,
    )

    private fun topRightSmall(): IntRect = IntRect(900, 60, 1050, 130)
    private fun topLeftSmall(): IntRect = IntRect(30, 60, 180, 130)

    private fun evaluateAllow(
        prop: ActionProposal,
        fresh: FreshTargetResolution,
        attempts: Int = 0,
        caseActive: Boolean = true,
        score: Int = 88,
        risks: List<WeightedReason> = emptyList(),
    ) = gate.evaluate(prop, true, caseActive, attempts, fresh, score, risks)

    // ---- positive cases ------------------------------------------------------

    @Test
    fun `unique top-right small skip with consistent revalidation is allowed`() {
        val cand = candidate("跳过", topRightSmall(), resourceId = "com.demo:id/skip")
        val prop = proposal(cand)
        val match = node(0, null, "com.demo:id/skip", "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertIs<Allow>(verdict)
    }

    @Test
    fun `unique top-left small skip is allowed`() {
        val cand = candidate("跳过", topLeftSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topLeftSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertIs<Allow>(verdict)
    }

    @Test
    fun `english skip label without resourceId is allowed when unique and consistent`() {
        val cand = candidate("Skip", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "Skip", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertIs<Allow>(verdict)
    }

    // ---- adversarial corpus: every scenario must BLOCK ----------------------

    @Test
    fun `central cross blocks`() {
        val cand = candidate("×", IntRect(430, 1050, 650, 1130))
        val prop = proposal(cand)
        val match = node(0, null, null, "×", IntRect(430, 1050, 650, 1130))

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertEquals(Block(ActuationBlockReason.GEOMETRY_CHANGED), verdict)
    }

    @Test
    fun `large close button blocks`() {
        val big = IntRect(100, 800, 980, 1100) // area ratio ~0.24 > 0.06
        val cand = candidate("关闭", big)
        val prop = proposal(cand)
        val match = node(0, null, null, "关闭", big)

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertEquals(Block(ActuationBlockReason.LARGE_TARGET), verdict)
    }

    @Test
    fun `cta sibling blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match), cta = true))

        assertEquals(Block(ActuationBlockReason.CTA_RISK), verdict)
    }

    @Test
    fun `skip with cta present blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match), cta = true))

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

        val verdict = evaluateAllow(prop, fresh(matches))

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

        val verdict = evaluateAllow(prop, fresh(matches))

        assertEquals(Block(ActuationBlockReason.MULTIPLE_TARGETS), verdict)
    }

    @Test
    fun `candidate moved after decision blocks as geometry change`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        // moved far down-left: normalized center distance > 0.05
        val moved = node(0, null, null, "跳过", IntRect(200, 900, 350, 970))

        val verdict = evaluateAllow(prop, fresh(listOf(moved)))

        assertEquals(Block(ActuationBlockReason.GEOMETRY_CHANGED), verdict)
    }

    @Test
    fun `candidate disappeared blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)

        val verdict = evaluateAllow(prop, fresh(emptyList()))

        assertEquals(Block(ActuationBlockReason.TARGET_DISAPPEARED), verdict)
    }

    @Test
    fun `package switched blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match), packageName = "com.evil.app"))

        assertEquals(Block(ActuationBlockReason.PACKAGE_CHANGED), verdict)
    }

    @Test
    fun `window identity changed blocks even with same count semantics`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand, windowToken = 10L)
        val match = node(0, null, null, "跳过", topRightSmall())

        // Same generation, but the window token changed: a splash was replaced
        // by a main window while the window *count* stayed the same.
        val verdict = evaluateAllow(prop, fresh(listOf(match), windowToken = 11L))

        assertEquals(Block(ActuationBlockReason.WINDOW_CHANGED), verdict)
    }

    @Test
    fun `generation moved on blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand, generation = 1L)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match), generation = 2L))

        assertEquals(Block(ActuationBlockReason.STALE_GENERATION), verdict)
    }

    @Test
    fun `fresh snapshot equal to proposal time blocks as revalidation stale`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand, elapsedMs = 1_000L)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match), capturedAtMs = 1_000L))

        assertEquals(Block(ActuationBlockReason.REVALIDATION_STALE), verdict)
    }

    @Test
    fun `fresh snapshot older than proposal blocks as revalidation stale`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand, elapsedMs = 2_000L)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match), capturedAtMs = 1_000L))

        assertEquals(Block(ActuationBlockReason.REVALIDATION_STALE), verdict)
    }

    @Test
    fun `second attempt on the same case blocks as attempt limit reached`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match)), attempts = 1)

        assertEquals(Block(ActuationBlockReason.ATTEMPT_LIMIT_REACHED), verdict)
    }

    @Test
    fun `large clickable ancestor blocks`() {
        val cand = candidate("跳过", topRightSmall(), ancestor = IntRect(0, 0, 1080, 2400))
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall(), ancestor = IntRect(0, 0, 1080, 2400))

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertEquals(Block(ActuationBlockReason.ANCESTOR_RISK), verdict)
    }

    @Test
    fun `invisible target blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall(), visible = false)

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertEquals(Block(ActuationBlockReason.NOT_CLICKABLE), verdict)
    }

    @Test
    fun `disabled target blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall(), enabled = false)

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertEquals(Block(ActuationBlockReason.NOT_CLICKABLE), verdict)
    }

    @Test
    fun `non-clickable target blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall(), clickable = false)

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertEquals(Block(ActuationBlockReason.NOT_CLICKABLE), verdict)
    }

    @Test
    fun `bottom-right skip blocks as outside allowed zone`() {
        val cand = candidate("跳过", IntRect(900, 2200, 1050, 2270))
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", IntRect(900, 2200, 1050, 2270))

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertEquals(Block(ActuationBlockReason.GEOMETRY_CHANGED), verdict)
    }

    @Test
    fun `bottom-left skip blocks as outside allowed zone`() {
        val cand = candidate("跳过", IntRect(30, 2200, 180, 2270))
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", IntRect(30, 2200, 180, 2270))

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertEquals(Block(ActuationBlockReason.GEOMETRY_CHANGED), verdict)
    }

    @Test
    fun `bottom-center skip blocks as outside allowed zone`() {
        val cand = candidate("跳过", IntRect(450, 2200, 630, 2270))
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", IntRect(450, 2200, 630, 2270))

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertEquals(Block(ActuationBlockReason.GEOMETRY_CHANGED), verdict)
    }

    @Test
    fun `revalidated score dropped below threshold blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match)), score = 62)

        assertEquals(Block(ActuationBlockReason.SCORE_DROPPED), verdict)
    }

    @Test
    fun `revalidated severe risk blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = evaluateAllow(
            prop,
            fresh(listOf(match)),
            risks = listOf(WeightedReason("cta_sibling", -100)),
        )

        assertEquals(Block(ActuationBlockReason.RISK_BLOCKED), verdict)
    }

    @Test
    fun `countdown anomaly blocks`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match), countdownAnomaly = true))

        assertEquals(Block(ActuationBlockReason.COUNTDOWN_ANOMALY), verdict)
    }

    @Test
    fun `ended case blocks as stale`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match)), caseActive = false)

        assertEquals(Block(ActuationBlockReason.CASE_STALE), verdict)
    }

    @Test
    fun `off switch after proposal blocks as mode blocked`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, modeAllowsAction = false, caseActive = true, attemptsAlreadyMade = 0, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.MODE_BLOCKED), verdict)
    }

    @Test
    fun `shadow mode never allows`() {
        val cand = candidate("跳过", topRightSmall())
        val prop = proposal(cand)
        val match = node(0, null, null, "跳过", topRightSmall())

        val verdict = gate.evaluate(prop, modeAllowsAction = false, caseActive = true, attemptsAlreadyMade = 0, fresh(listOf(match)), 88, emptyList())

        assertEquals(Block(ActuationBlockReason.MODE_BLOCKED), verdict)
    }

    @Test
    fun `resourceId identity mismatch blocks`() {
        val cand = candidate("跳过", topRightSmall(), resourceId = "com.demo:id/skip_a")
        val prop = proposal(cand)
        val match = node(0, null, "com.demo:id/skip_b", "跳过", topRightSmall())

        val verdict = evaluateAllow(prop, fresh(listOf(match)))

        assertEquals(Block(ActuationBlockReason.IDENTITY_AMBIGUOUS), verdict)
    }
}
