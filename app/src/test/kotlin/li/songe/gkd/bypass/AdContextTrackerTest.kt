package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ad context levels: STRONG from mini-program hosts / skip evidence / ad
 * labels; WEAK from a fresh entry with only a close candidate; NONE otherwise.
 * A service reconnect is not a launch (entry times only move on real
 * package/activity changes).
 */
class AdContextTrackerTest {

    @Test
    fun mini_program_activity_is_strong() {
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluate(
                "com.tencent.mm",
                "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
                now = System.currentTimeMillis(),
            ),
        )
        assertEquals(
            BypassAdContextLevel.STRONG,
            BypassAdContextTracker.evaluate(
                "com.eg.android.AlipayGphone",
                "com.alipay.mobile.nebulax.remote.biz.XRiverActivity",
                now = System.currentTimeMillis(),
            ),
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
    fun ad_label_evidence_makes_context_strong() {
        val pkg = "com.example.adhost2"
        val act = "com.example.Splash"
        BypassAdContextTracker.resetWindow(pkg, act)
        BypassAdContextTracker.noteAdLabel(pkg, act)
        assertEquals(
            BypassAdContextLevel.STRONG,
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

    // ---- P0-6: a service reconnect must not re-open the startup window ----

    @Test
    fun reconnect_first_fresh_read_does_not_reanchor() {
        // A fresh engine / service reconnect starts with an empty observedTopApp
        // baseline. The first fresh root read of the SAME app is only a
        // baseline, never a transition: it must not call onAppObserved(now).
        assertTrue(!BypassWindowAnchor.shouldReanchorOnObservation("", "com.tencent.mm"))
        // A genuine A -> B transition while the service is up DOES re-anchor.
        assertTrue(BypassWindowAnchor.shouldReanchorOnObservation("com.tencent.mm", "com.example.app"))
        // Re-reads of the same app never re-anchor.
        assertTrue(!BypassWindowAnchor.shouldReanchorOnObservation("com.tencent.mm", "com.tencent.mm"))
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
        assertTrue(!BypassWindowAnchor.shouldReanchorOnObservation("", pkg))
        // Without re-anchoring the startup window is closed:
        assertTrue(!BypassAdContextTracker.freshEntry(pkg, act, t0 + 60_000L))
        // A real transition to a different app DOES anchor a fresh window:
        val tLaunch = t0 + 60_000L
        BypassAdContextTracker.onAppObserved("com.example.adhost", tLaunch)
        assertTrue(BypassAdContextTracker.freshEntry("com.example.adhost", null, tLaunch + 1_000L))
        BypassAdContextTracker.clearForTest()
    }
}
