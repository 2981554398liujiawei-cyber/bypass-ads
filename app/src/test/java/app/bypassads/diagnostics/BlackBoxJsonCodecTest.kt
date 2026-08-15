package app.bypassads.diagnostics

import app.bypassads.core.model.CandidateFeatures
import app.bypassads.core.model.DecisionType
import app.bypassads.core.model.IntRect
import app.bypassads.core.model.SkipCandidate
import app.bypassads.core.model.SkipDecision
import app.bypassads.core.model.WeightedReason
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlackBoxJsonCodecTest {
    @Test
    fun `round trip preserves privacy-minimized candidate decision fields`() {
        val feature = CandidateFeatures(4, "skip", "com.demo:id/ad_skip", IntRect(900, 60, 1050, 130), true, true, true, IntRect(890, 50, 1060, 140), 1080, 2400, 3, 4, true, true, true, true)
        val decision = SkipDecision(DecisionType.TOO_RISKY, SkipCandidate(feature, 73, listOf(WeightedReason("label_skip", 24)), listOf(WeightedReason("identity_ambiguous", -100))), 80, "severe_risk")
        val record = BlackBoxRecord(10L, 7L, 2, "com.demo", BlackBoxTrigger.SCAN, BlackBoxTrigger.CONTENT_CHANGED, 1, 12, listOf(feature), decision, 141L, 9L)

        val decoded = assertNotNull(BlackBoxJsonCodec.decodeOrNull(BlackBoxJsonCodec.encode(record)))
        val candidate = decoded.candidates.single()
        assertEquals(listOf(900, 60, 1050, 130), candidate.bounds)
        assertEquals(feature.resourceId, candidate.resourceId)
        assertTrue(candidate.identityAmbiguous)
        assertTrue(candidate.countdownStepObserved)
        assertTrue(candidate.positionDriftDetected)
        assertTrue(candidate.ctaSiblingDetected)
        assertEquals("TOO_RISKY", decoded.decision)
        assertEquals("severe_risk", decoded.rejectionReason)
        assertEquals(9L, decoded.scanWorkMs)
    }

    @Test
    fun `legacy JSON without identity ambiguity defaults false`() {
        val record = assertNotNull(BlackBoxJsonCodec.decodeOrNull("""{"time":1,"trigger":"SCAN","features":[{"label":"skip","bounds":[1,2,3,4]}]}"""))
        assertFalse(record.candidates.single().identityAmbiguous)
    }
}
