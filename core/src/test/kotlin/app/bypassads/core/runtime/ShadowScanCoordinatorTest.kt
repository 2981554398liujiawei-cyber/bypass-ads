package app.bypassads.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShadowScanCoordinatorTest {
    @Test fun `unknown events coalesce and known event promotes the same case`() {
        val coordinator = ShadowScanCoordinator()
        val first = assertIs<ShadowCoordinatorAction.Start>(coordinator.onForeground(PackageIdentity.Unknown, true))
        assertIs<ShadowCoordinatorAction.Coalesced>(coordinator.onForeground(PackageIdentity.Unknown, true))
        assertIs<ShadowCoordinatorAction.Coalesced>(coordinator.onForeground(PackageIdentity.Known("a"), true))
        assertTrue(coordinator.tryStartFrame(first.generation))
    }

    @Test fun `ended generations can never append a scan`() {
        val coordinator = ShadowScanCoordinator()
        val started = assertIs<ShadowCoordinatorAction.Start>(coordinator.onForeground(PackageIdentity.Known("a"), true))
        assertTrue(coordinator.tryStartFrame(started.generation))
        assertIs<ShadowCoordinatorAction.End>(coordinator.end(ShadowCaseEndReason.MODE_OFF))
        assertFalse(coordinator.canAppendScan(started.generation))
    }

    @Test fun `all busy dropped frames terminate as scan busy with balanced accounting`() {
        val coordinator = ShadowScanCoordinator(3)
        val started = assertIs<ShadowCoordinatorAction.Start>(coordinator.onForeground(PackageIdentity.Known("a"), true))
        coordinator.markWorkerBusyForPreviousCase()
        assertFalse(coordinator.tryStartFrame(started.generation))
        assertFalse(coordinator.tryStartFrame(started.generation))
        assertFalse(coordinator.tryStartFrame(started.generation))
        val end = assertIs<ShadowCoordinatorAction.End>(coordinator.workerFinished())
        assertEquals(ShadowCaseEndReason.SCAN_BUSY, end.reason)
        assertEquals(3, end.snapshot.executedFrames + end.snapshot.cancelledFrames + end.snapshot.droppedFrames)
    }

    @Test fun `package supersession closes before a replacement starts`() {
        val coordinator = ShadowScanCoordinator()
        assertIs<ShadowCoordinatorAction.Start>(coordinator.onForeground(PackageIdentity.Known("a"), true))
        assertIs<ShadowCoordinatorAction.End>(coordinator.onForeground(PackageIdentity.Known("b"), true))
        assertIs<ShadowCoordinatorAction.Start>(coordinator.startPending())
    }
}
