package app.bypassads.core.actuation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * M2.2 trial protection: kill switch OFF blocks immediately, budget exhaustion
 * auto-pauses, non-allowlisted packages are Shadow-only, and ALLOW still
 * requires the full gate chain downstream.
 */
class ExperimentalTrialGuardTest {

    private fun guard(
        allowlist: Set<String> = setOf("com.trial.app"),
        max: Int = 3,
    ) = ExperimentalTrialGuard(allowlist, max)

    @Test
    fun `disabled guard blocks everything as kill switch off`() {
        val g = guard()

        assertEquals(TrialGuardDecision.KILL_SWITCH_OFF, g.evaluate("com.trial.app"))
        assertEquals(TrialGuardDecision.KILL_SWITCH_OFF, g.evaluate(null))
    }

    @Test
    fun `enabled guard allows allowlisted package with budget`() {
        val g = guard()
        g.enabled = true

        assertEquals(TrialGuardDecision.ALLOW, g.evaluate("com.trial.app"))
    }

    @Test
    fun `enabled guard blocks non-allowlisted package`() {
        val g = guard()
        g.enabled = true

        assertEquals(TrialGuardDecision.PACKAGE_NOT_ALLOWLISTED, g.evaluate("com.wechat"))
        assertEquals(TrialGuardDecision.PACKAGE_NOT_ALLOWLISTED, g.evaluate(null))
    }

    @Test
    fun `kill switch off after being enabled blocks immediately`() {
        val g = guard()
        g.enabled = true
        assertEquals(TrialGuardDecision.ALLOW, g.evaluate("com.trial.app"))

        g.enabled = false // kill switch

        assertEquals(TrialGuardDecision.KILL_SWITCH_OFF, g.evaluate("com.trial.app"))
    }

    @Test
    fun `budget exhaustion auto-pauses the trial`() {
        val g = guard(max = 2)
        g.enabled = true

        assertEquals(TrialGuardDecision.ALLOW, g.evaluate("com.trial.app"))
        g.recordAction()
        assertEquals(TrialGuardDecision.ALLOW, g.evaluate("com.trial.app"))
        g.recordAction()
        assertEquals(0, g.actionBudgetRemaining)

        assertEquals(TrialGuardDecision.ACTION_BUDGET_EXHAUSTED, g.evaluate("com.trial.app"))
    }

    @Test
    fun `record action beyond budget fails fast`() {
        val g = guard(max = 1)
        g.enabled = true
        g.recordAction()

        assertFailsWith<IllegalArgumentException> { g.recordAction() }
    }

    @Test
    fun `default budget is twenty actions per session`() {
        val g = ExperimentalTrialGuard(allowlist = setOf("com.trial.app"))

        assertEquals(20, g.actionBudgetRemaining)
    }

    @Test
    fun `budget survives guard recreation via restore (service rebuild cannot refill)`() {
        val g1 = guard(max = 3)
        g1.enabled = true
        g1.recordAction()
        g1.recordAction()
        assertEquals(1, g1.actionBudgetRemaining)

        // Simulated service/process rebuild: new guard instance restores the
        // persisted remaining budget — it must NOT go back to 3.
        val g2 = guard(max = 3)
        g2.enabled = true
        g2.restoreBudget(1)

        assertEquals(1, g2.actionBudgetRemaining)
        assertEquals(TrialGuardDecision.ALLOW, g2.evaluate("com.trial.app"))
        g2.recordAction()
        assertEquals(0, g2.actionBudgetRemaining)
        assertEquals(TrialGuardDecision.ACTION_BUDGET_EXHAUSTED, g2.evaluate("com.trial.app"))
    }

    @Test
    fun `restore budget clamps outside the legal range`() {
        val g = guard(max = 3)
        g.restoreBudget(99)
        assertEquals(3, g.actionBudgetRemaining)
        g.restoreBudget(-5)
        assertEquals(0, g.actionBudgetRemaining)
    }

    @Test
    fun `allow does not bypass the gate - gate still blocks cta`() {
        val g = guard()
        g.enabled = true
        assertEquals(TrialGuardDecision.ALLOW, g.evaluate("com.trial.app"))

        // The guard only opens the door; the fail-closed gate is still the
        // single authorization entry (covered in depth by ActuationSessionTest).
        val gate = ActuationGate()
        val fresh = FreshTargetResolver().resolve(
            snapshot = app.bypassads.core.model.UiSnapshot(
                packageName = "com.trial.app",
                screenWidth = 1080,
                screenHeight = 2400,
                capturedAtElapsedMs = 2_000L,
                windowCount = 1,
                nodes = listOf(
                    node(0, "跳过"),
                    node(1, "立即体验"),
                ),
            ),
            packageName = "com.trial.app",
            caseGeneration = 1L,
            windowContextToken = 10L,
            capturedAtElapsedMs = 2_000L,
            fingerprint = fingerprint(),
        )
        val verdict = gate.evaluate(
            proposal = proposal(),
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

    private fun node(index: Int, text: String) = app.bypassads.core.model.UiNodeSnapshot(
        index = index,
        parentIndex = null,
        resourceId = null,
        text = text,
        contentDescription = null,
        className = "android.widget.Button",
        packageName = "com.trial.app",
        bounds = app.bypassads.core.model.IntRect(900, 60, 1050, 130),
        clickable = true,
        enabled = true,
        visibleToUser = true,
        depth = 2,
        nearestClickableAncestorBounds = null,
    )

    private fun fingerprint() = TargetFingerprint(
        label = "跳过",
        packageName = "com.trial.app",
        resourceId = null,
        bounds = app.bypassads.core.model.IntRect(900, 60, 1050, 130),
        screenWidth = 1080,
        screenHeight = 2400,
    )

    private fun proposal() = ActionProposal.fromDecision(
        app.bypassads.core.model.SkipDecision(
            app.bypassads.core.model.DecisionType.WOULD_CLICK,
            app.bypassads.core.model.SkipCandidate(
                features = app.bypassads.core.model.CandidateFeatures(
                    nodeIndex = 0, label = "跳过", resourceId = null,
                    bounds = app.bypassads.core.model.IntRect(900, 60, 1050, 130),
                    clickable = true, enabled = true, visibleToUser = true,
                    nearestClickableAncestorBounds = null,
                    screenWidth = 1080, screenHeight = 2400,
                    stabilityHits = 2, countdownStepObserved = true,
                ),
                score = 88, evidence = emptyList(), risks = emptyList(),
            ),
            80,
        ),
        "com.trial.app",
        1L,
        10L,
        1_000L,
    )!!
}
