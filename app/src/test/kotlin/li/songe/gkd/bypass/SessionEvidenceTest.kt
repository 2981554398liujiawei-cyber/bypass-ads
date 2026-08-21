package li.songe.gkd.bypass

import li.songe.gkd.data.BypassDetectionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-2 (multi-stage ads): session evidence is the MOST RECENT acted
 * candidate, not the sticky first one. evidenceOf is the pure function the
 * verifier consumes — it must never be confused by earlier candidates of the
 * same session.
 */
class SessionEvidenceTest {

    private fun record(
        candidateType: String? = null,
        ruleKey: Int? = null,
        groupKey: Int? = null,
        bounds: String? = null,
    ) = BypassDetectionSession(
        sessionId = "s1",
        packageName = "com.example.adhost",
        activityName = "com.example.Splash",
        startTime = 0L,
        candidateType = candidateType,
        actedRuleKey = ruleKey,
        actedGroupKey = groupKey,
        actedCandidateBounds = bounds,
    )

    @Test
    fun no_acted_candidate_yields_null_evidence() {
        assertNull(BypassDetectionSessions.evidenceOf(record()))
        // Candidate type seen but no acted rule identity/bounds: the evidence
        // still carries the candidate type (it is part of the evidence).
        val onlyType = BypassDetectionSessions.evidenceOf(record(candidateType = "SKIP_TEXT"))
        assertEquals(BypassExitCandidateType.SKIP_TEXT, onlyType?.candidateType)
        assertNull(onlyType?.ruleKey)
        assertNull(onlyType?.bounds)
    }

    @Test
    fun evidence_reflects_the_latest_acted_candidate() {
        // The record fields hold the LAST action: skip (rule 1) then close
        // (rule 2). The evidence must be the close, not the skip.
        val r = record(
            candidateType = "CLOSE_TEXT",
            ruleKey = 2,
            groupKey = 10,
            bounds = "100,200,300,400",
        )
        val evidence = BypassDetectionSessions.evidenceOf(r)
        assertEquals(BypassExitCandidateType.CLOSE_TEXT, evidence?.candidateType)
        assertEquals(2, evidence?.ruleKey)
        assertEquals(10, evidence?.groupKey)
        assertEquals("100,200,300,400", evidence?.bounds)
    }

    @Test
    fun unknown_candidate_type_does_not_throw() {
        // A malformed/legacy candidate type string must degrade to null type,
        // not crash the verifier path.
        val r = record(candidateType = "LEGACY_WEIRD", ruleKey = 5, groupKey = 7)
        val evidence = BypassDetectionSessions.evidenceOf(r)
        assertNull(evidence?.candidateType)
        assertEquals(5, evidence?.ruleKey)
        assertEquals(7, evidence?.groupKey)
    }

    @Test
    fun bounds_without_rule_identity_still_yields_region_evidence() {
        // Teach-era sessions may have bounds but no structured keys: the
        // verifier can still do a same-ad-region re-check.
        val r = record(candidateType = "CLOSE_ICON", bounds = "1,2,3,4")
        val evidence = BypassDetectionSessions.evidenceOf(r)
        assertEquals(BypassExitCandidateType.CLOSE_ICON, evidence?.candidateType)
        assertNull(evidence?.ruleKey)
        assertEquals("1,2,3,4", evidence?.bounds)
    }

    @Test
    fun acted_fields_are_latest_not_sticky_first() {
        // DB fields store the MOST RECENT action (Skip then Close): a later
        // Close must overwrite the first Skip, never stay sticky on it.
        val first = record(
            candidateType = "SKIP_TEXT",
            ruleKey = 1,
            groupKey = 10,
            bounds = "10,20,90,60",
        )
        val latest = first.copy(
            candidateType = "CLOSE_TEXT",
            actedRuleKey = 2,
            actedGroupKey = 10,
            actedCandidateBounds = "200,300,280,360",
        )
        val evidence = BypassDetectionSessions.evidenceOf(latest)!!
        assertEquals(BypassExitCandidateType.CLOSE_TEXT, evidence.candidateType)
        assertEquals(2, evidence.ruleKey)
        assertEquals("200,300,280,360", evidence.bounds)
        // The first Skip identity is gone from the record.
        assertTrue(evidence.ruleKey != first.actedRuleKey)
    }
}
