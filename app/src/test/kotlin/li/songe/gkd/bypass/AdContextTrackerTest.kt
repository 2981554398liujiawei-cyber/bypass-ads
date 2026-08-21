package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ad context levels: STRONG only from real ad evidence (skip candidate / ad
 * label); a mini-program shell (AppBrandUI) alone is WEAK (P0-3); WEAK from
 * a fresh entry with only a close candidate; NONE otherwise. A service
 * reconnect is not a launch (entry times only move on real package/activity
 * changes; the anchor is four-state, P0-5).
 */
class AdContextTrackerTest {

    @Test
    fun mini_program_activity_alone_is_weak_not_strong() {
        // P0-3: AppBrandUI alone is NOT ad proof (星星充电主页/场站页/会员页
        // all run inside AppBrandUI). Only real ad evidence makes it STRONG.
        val pkg = "com.tencent.mm"
        val act = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"
        BypassAdContextTracker.resetWindow(pkg, act)
        assertEquals(
            BypassAdContextLevel.WEAK,
            BypassAdContextTracker.evaluate(
                pkg,
                act,
                now = System.currentTimeMillis(),
            ),
        )
        val pkg2 = "com.eg.android.AlipayGphone"
        val act2 = "com.alipay.mobile.nebulax.remote.biz.XRiverActivity"
        BypassAdContextTracker.resetWindow(pkg2, act2)
        assertEquals(
            BypassAdContextLevel.WEAK,
            BypassAdContextTracker.evaluate(
                pkg2,
                act2,
                now = System.currentTimeMillis(),
            ),
        )
    }

    @Test
    fun mini_program_activity_with_skip_evidence_is_strong() {
        // AppBrandUI + real ad evidence (explicit skip candidate) => STRONG.
        val pkg = "com.tencent.mm"
        val act = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"
        BypassAdContextTracker.resetWindow(pkg, act)
        BypassAdContextTracker.noteCandidate(pkg, act, BypassExitCandidateType.SKIP_TEXT)
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluate(pkg, act, now = System.currentTimeMillis()),
        )
    }

    @Test
    fun skip_evidence_makes_context_strong() {
        val pkg = "com.example.adhost"
        val act = "com.example.Splash"
        BypassAdContextTracker.resetWindow(pkg, act)
        BypassAdContextTracker.noteCandidate(pkg, act, BypassExitCandidateType.SKIP_TEXT)
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluate(pkg, act, now = System.currentTimeMillis()),
        )
    }

    @Test
    fun ad_specific_view_id_evidence_makes_context_strong() {
        // P0-1: STRONG requires evidence RELATED to the current ad. An
        // ad-specific ViewId (ad_close / splash_close / close_ad) seen on the
        // candidate is real ad evidence (windowAdViewIdSeen).
        val pkg = "com.example.adhost2"
        val act = "com.example.Splash"
        BypassAdContextTracker.resetWindow(pkg, act)
        BypassAdContextTracker.noteCandidate(pkg, act, BypassExitCandidateType.CLOSE_VIEW_ID, viewId = "ad_close")
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluate(pkg, act, now = System.currentTimeMillis()),
        )
    }

    @Test
    fun ordinary_close_view_id_is_weak_not_strong() {
        // P0-1: a close candidate with a generic view id (not ad-specific)
        // is only WEAK — it must not upgrade the whole window.
        val pkg = "com.example.adhost4"
        val act = "com.example.Splash"
        BypassAdContextTracker.resetWindow(pkg, act)
        BypassAdContextTracker.onTopActivityChanged(pkg, act, System.currentTimeMillis())
        BypassAdContextTracker.noteCandidate(pkg, act, BypassExitCandidateType.CLOSE_VIEW_ID, viewId = "iv_close")
        assertEquals(
            BypassAdContextLevel.WEAK,
            BypassAdContextTracker.evaluate(pkg, act, now = System.currentTimeMillis()),
        )
    }

    @Test
    fun close_text_evidence_is_weak_not_strong() {
        // P0-1: CLOSE_TEXT alone is not STRONG (only skip / ad view ids are).
        val pkg = "com.example.adhost5"
        val act = "com.example.Splash"
        BypassAdContextTracker.resetWindow(pkg, act)
        BypassAdContextTracker.onTopActivityChanged(pkg, act, System.currentTimeMillis())
        BypassAdContextTracker.noteCandidate(pkg, act, BypassExitCandidateType.CLOSE_TEXT)
        assertEquals(
            BypassAdContextLevel.WEAK,
            BypassAdContextTracker.evaluate(pkg, act, now = System.currentTimeMillis()),
        )
    }

    @Test
    fun close_candidate_in_fresh_entry_is_weak_not_strong() {
        val pkg = "com.example.adhost3"
        val act = "com.example.Splash"
        BypassAdContextTracker.resetWindow(pkg, act)
        val now = System.currentTimeMillis()
        BypassAdContextTracker.onTopActivityChanged(pkg, act, now)
        BypassAdContextTracker.noteCandidate(pkg, act, BypassExitCandidateType.CLOSE_TEXT)
        assertEquals(BypassAdContextLevel.WEAK, BypassAdContextTracker.evaluate(pkg, act, now = now))
    }

    @Test
    fun no_evidence_is_none() {
        val pkg = "com.example.plain"
        val act = "com.example.Main"
        BypassAdContextTracker.resetWindow(pkg, act)
        assertEquals(
            BypassAdContextLevel.NONE,
            BypassAdContextTracker.evaluate(pkg, act, now = System.currentTimeMillis()),
        )
    }

    @Test
    fun activity_reentry_refreshes_the_window() {
        val pkg = "com.tencent.mm"
        val act = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"
        val t1 = System.currentTimeMillis()
        BypassAdContextTracker.onTopActivityChanged(pkg, act, t1)
        val t2 = t1 + 60_000L
        BypassAdContextTracker.onTopActivityChanged(pkg, act, t2)
        assert(BypassAdContextTracker.freshEntry(pkg, act, t2 + 1_000L))
    }

    // ---- P0-5: four-state window anchor ----

    @Test
    fun reconnect_first_fresh_read_does_not_reanchor() {
        // A fresh engine / service reconnect starts with an empty observedTopApp
        // baseline. The first fresh root read of the SAME app is only a
        // baseline, never a transition.
        assertEquals(
            BypassWindowAnchorObservationType.SERVICE_RECONNECT,
            BypassWindowAnchor.classifyObservation("", "com.tencent.mm"),
        )
        // A genuine A -> B transition while the service is up DOES re-anchor.
        assertEquals(
            BypassWindowAnchorObservationType.REAL_PACKAGE_CHANGE,
            BypassWindowAnchor.classifyObservation("com.tencent.mm", "com.example.app"),
        )
        // Re-reads of the same app never re-anchor.
        assertEquals(
            BypassWindowAnchorObservationType.ENGINE_FALLBACK_OBSERVATION,
            BypassWindowAnchor.classifyObservation("com.tencent.mm", "com.tencent.mm"),
        )
    }

    @Test
    fun normal_screen_60s_then_reconnect_stays_outside_startup_window() {
        BypassAdContextTracker.clearForTest()
        val pkg = "com.example.normal"
        val act = "com.example.Main"
        val t0 = 1_000_000L
        // User entered a normal screen (not a mini-program ad activity).
        BypassAdContextTracker.onTopActivityChanged(pkg, act, t0)
        assertTrue(BypassAdContextTracker.freshEntry(pkg, act, t0 + 1_000L))

        // 60s later the accessibility service reconnects. The first fresh
        // root read after reconnect must NOT re-anchor the window (empty
        // baseline), so the entry time stays at t0:
        val reconnect = BypassWindowAnchor.classifyObservation("", pkg)
        assertEquals(BypassWindowAnchorObservationType.SERVICE_RECONNECT, reconnect)
        BypassAdContextTracker.onAppObserved(pkg, t0 + 60_000L, reconnect)
        // Without re-anchoring the startup window is closed:
        assertTrue(!BypassAdContextTracker.freshEntry(pkg, act, t0 + 60_000L))
        // A real transition to a different app DOES anchor a fresh window:
        val tLaunch = t0 + 60_000L
        BypassAdContextTracker.onAppObserved("com.example.adhost", tLaunch)
        assertTrue(BypassAdContextTracker.freshEntry("com.example.adhost", null, tLaunch + 1_000L))
        BypassAdContextTracker.clearForTest()
    }

    @Test
    fun package_transition_refreshes_entry_even_when_returning() {
        // Acceptance: A -> B -> 60s -> B (activity=null) => second B entry is
        // a fresh startup window again.
        BypassAdContextTracker.clearForTest()
        val t0 = 2_000_000L
        BypassAdContextTracker.onTopActivityChanged("com.app.a", null, t0)
        BypassAdContextTracker.onTopActivityChanged("com.app.b", null, t0 + 1_000L)
        assertTrue(BypassAdContextTracker.freshEntry("com.app.b", null, t0 + 2_000L))
        BypassAdContextTracker.onTopActivityChanged("com.app.a", null, t0 + 10_000L)
        // 60s later B is foreground again: the package entry time MUST refresh.
        BypassAdContextTracker.onTopActivityChanged("com.app.b", null, t0 + 70_000L)
        assertTrue(
            "second B entry must open a fresh startup window",
            BypassAdContextTracker.freshEntry("com.app.b", null, t0 + 71_000L),
        )
        BypassAdContextTracker.clearForTest()
    }

    @Test
    fun same_package_activity_change_opens_fresh_activity_window() {
        // Acceptance: WeChat LauncherUI -> AppBrandUI => fresh activity window.
        BypassAdContextTracker.clearForTest()
        val t0 = 3_000_000L
        BypassAdContextTracker.onTopActivityChanged(
            "com.tencent.mm",
            "com.tencent.mm.ui.LauncherUI",
            t0,
        )
        BypassAdContextTracker.onTopActivityChanged(
            "com.tencent.mm",
            "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
            t0 + 5_000L,
        )
        assertTrue(
            "LauncherUI -> AppBrandUI must open a fresh activity window",
            BypassAdContextTracker.freshEntry(
                "com.tencent.mm",
                "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
                t0 + 6_000L,
            ),
        )
        BypassAdContextTracker.clearForTest()
    }

    @Test
    fun engine_fallback_observation_of_same_app_never_refreshes() {
        BypassAdContextTracker.clearForTest()
        val t0 = 4_000_000L
        BypassAdContextTracker.onAppObserved("com.tencent.mm", t0)
        // A re-read of the same foreground app 60s later (no transition):
        BypassAdContextTracker.onAppObserved(
            "com.tencent.mm",
            t0 + 60_000L,
            BypassWindowAnchorObservationType.ENGINE_FALLBACK_OBSERVATION,
        )
        assertTrue(!BypassAdContextTracker.freshEntry("com.tencent.mm", null, t0 + 61_000L))
        BypassAdContextTracker.clearForTest()
    }

    // ---- P0-1: transient evidence lifecycle ----

    @Test
    fun skip_evidence_is_cleared_on_package_change() {
        // After a real package change the old window's skip evidence must
        // never leak into the next window (previous splash STRONG must not
        // pollute the normal page).
        BypassAdContextTracker.clearForTest()
        val t0 = 5_000_000L
        BypassAdContextTracker.onTopActivityChanged("com.app.splash", "com.app.Splash", t0)
        BypassAdContextTracker.noteCandidate("com.app.splash", "com.app.Splash", BypassExitCandidateType.SKIP_TEXT)
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluate("com.app.splash", "com.app.Splash", now = t0 + 1_000L),
        )
        // Real transition into the normal page of ANOTHER package:
        BypassAdContextTracker.onTopActivityChanged("com.app.normal", "com.app.Main", t0 + 5_000L)
        assertEquals(
            BypassAdContextLevel.NONE,
            BypassAdContextTracker.evaluate("com.app.normal", "com.app.Main", now = t0 + 6_000L),
        )
        // And the old window's evidence is gone even after returning:
        assertEquals(
            BypassAdContextLevel.NONE,
            BypassAdContextTracker.evaluate("com.app.splash", "com.app.Splash", now = t0 + 6_000L),
        )
        BypassAdContextTracker.clearForTest()
    }

    @Test
    fun skip_evidence_is_cleared_on_activity_change() {
        // WeChat LauncherUI -> AppBrandUI normal page: the previous
        // splash-window STRONG (from another activity) must not survive.
        BypassAdContextTracker.clearForTest()
        val t0 = 6_000_000L
        BypassAdContextTracker.onTopActivityChanged("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", t0)
        BypassAdContextTracker.noteCandidate(
            "com.tencent.mm",
            "com.tencent.mm.ui.LauncherUI",
            BypassExitCandidateType.SKIP_TEXT,
        )
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluate("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", now = t0 + 1_000L),
        )
        BypassAdContextTracker.onTopActivityChanged(
            "com.tencent.mm",
            "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
            t0 + 5_000L,
        )
        assertEquals(
            BypassAdContextLevel.WEAK,
            BypassAdContextTracker.evaluate(
                "com.tencent.mm",
                "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
                now = t0 + 6_000L,
            ),
        )
        BypassAdContextTracker.clearForTest()
    }

    @Test
    fun terminal_clear_removes_window_evidence() {
        // SUCCESS_CONFIRMED / MISCLICK_SUSPECTED / FAILURE_CONFIRMED call
        // clearWindowEvidence: after the ad is gone the window returns to
        // WEAK/NONE instead of staying STRONG forever.
        BypassAdContextTracker.clearForTest()
        val t0 = 7_000_000L
        BypassAdContextTracker.onTopActivityChanged("com.app.ad", "com.app.Splash", t0)
        BypassAdContextTracker.noteCandidate("com.app.ad", "com.app.Splash", BypassExitCandidateType.SKIP_TEXT)
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluate("com.app.ad", "com.app.Splash", now = t0 + 1_000L),
        )
        BypassAdContextTracker.clearWindowEvidence("com.app.ad", "com.app.Splash")
        assertEquals(
            BypassAdContextLevel.NONE,
            BypassAdContextTracker.evaluate("com.app.ad", "com.app.Splash", now = t0 + 2_000L),
        )
        BypassAdContextTracker.clearForTest()
    }

    @Test
    fun same_activity_ad_refresh_does_not_clear_evidence() {
        // P0-1: within ONE ad window, normal ad refresh (new skip candidate
        // for the same activity) must NOT clear the transient evidence.
        BypassAdContextTracker.clearForTest()
        val t0 = 8_000_000L
        BypassAdContextTracker.onTopActivityChanged("com.app.ad", "com.app.Splash", t0)
        BypassAdContextTracker.noteCandidate("com.app.ad", "com.app.Splash", BypassExitCandidateType.SKIP_TEXT)
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluate("com.app.ad", "com.app.Splash", now = t0 + 1_000L),
        )
        // Second skip candidate of the same refresh cycle:
        BypassAdContextTracker.noteCandidate("com.app.ad", "com.app.Splash", BypassExitCandidateType.SKIP_TEXT)
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluate("com.app.ad", "com.app.Splash", now = t0 + 2_000L),
        )
        BypassAdContextTracker.clearForTest()
    }

    // ---- P0-1 hard gates: far banner / previous splash must not mint STRONG ----

    @Test
    fun far_banner_cannot_upgrade_window_to_strong() {
        // A. AppBrandUI + far-away page banner "广告" + ordinary small ImageView:
        // page-wide ad labels are a no-op (noteAdLabelFromWindow) and must never
        // mint STRONG for the whole window. The shell itself stays WEAK, so
        // Crazy structural close is denied (see BypassStrategyMatrixTest).
        BypassAdContextTracker.clearForTest()
        val pkg = "com.tencent.mm"
        val act = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"
        val t0 = 9_000_000L
        BypassAdContextTracker.onTopActivityChanged(pkg, act, t0)
        // noteAdLabelFromWindow is a production no-op (P0-1): a far-away
        // "广告" banner on the same AppBrandUI must never mint STRONG.
        assertEquals(
            BypassAdContextLevel.WEAK,
            BypassAdContextTracker.evaluate(pkg, act, now = t0 + 1_000L),
        )
        // Candidate-scoped evaluation with no near-label scan (null root /
        // null bounds) must also stay WEAK: a far banner is not current-ad
        // evidence.
        assertEquals(
            BypassAdContextLevel.WEAK,
            BypassAdContextTracker.evaluateCandidateContext(
                pkg, act, root = null, candidateBounds = "10,10,70,70", now = t0 + 1_000L,
            ),
        )
        BypassAdContextTracker.clearForTest()
    }

    @Test
    fun previous_splash_strong_does_not_pollute_following_normal_page() {
        // B. A successful splash Skip must not leave STRONG on the following
        // AppBrandUI normal page (terminal clear + activity change).
        BypassAdContextTracker.clearForTest()
        val t0 = 10_000_000L
        BypassAdContextTracker.onTopActivityChanged(
            "com.tencent.mm",
            "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
            t0,
        )
        BypassAdContextTracker.noteCandidate(
            "com.tencent.mm",
            "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
            BypassExitCandidateType.SKIP_TEXT,
        )
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluate(
                "com.tencent.mm",
                "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
                now = t0 + 1_000L,
            ),
        )
        // SUCCESS_CONFIRMED of the splash session:
        BypassAdContextTracker.clearWindowEvidence(
            "com.tencent.mm",
            "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
        )
        assertEquals(
            BypassAdContextLevel.WEAK,
            BypassAdContextTracker.evaluate(
                "com.tencent.mm",
                "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
                now = t0 + 2_000L,
            ),
        )
        BypassAdContextTracker.clearForTest()
    }

    @Test
    fun skip_in_same_window_keeps_strong_for_later_close() {
        // D. Same ad: Skip is recognized, then Close can use the current
        // session's real ad evidence (windowSkipSeen) as STRONG.
        BypassAdContextTracker.clearForTest()
        val t0 = 11_000_000L
        val pkg = "com.example.adhost"
        val act = "com.example.Splash"
        BypassAdContextTracker.onTopActivityChanged(pkg, act, t0)
        BypassAdContextTracker.noteCandidate(
            pkg, act, BypassExitCandidateType.SKIP_TEXT, bounds = "100,200,180,240",
        )
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluateCandidateContext(
                pkg,
                act,
                root = null,
                candidateBounds = "110,210,170,230",
                candidateType = BypassExitCandidateType.CLOSE_TEXT,
                now = t0 + 500L,
            ),
        )
        BypassAdContextTracker.clearForTest()
    }

    @Test
    fun leftover_skip_does_not_strong_unrelated_structural_close() {
        // P0-1 / TestAd AC: a previous Skip in this Activity (even nearby)
        // must not STRONG an unrelated STRUCTURAL_CLOSE / small ImageView.
        BypassAdContextTracker.clearForTest()
        val t0 = 12_000_000L
        val pkg = "app.bypassads.testad"
        val act = "app.bypassads.testad.MainActivity"
        BypassAdContextTracker.onTopActivityChanged(pkg, act, t0)
        BypassAdContextTracker.noteCandidate(
            pkg, act, BypassExitCandidateType.SKIP_TEXT, bounds = "900,200,1080,260",
        )
        assertEquals(
            BypassAdContextLevel.NONE,
            BypassAdContextTracker.evaluateCandidateContext(
                pkg,
                act,
                root = null,
                candidateBounds = "900,200,980,260",
                candidateType = BypassExitCandidateType.STRUCTURAL_CLOSE,
                now = t0 + 500L,
            ),
        )
        BypassAdContextTracker.clearForTest()
    }
}
