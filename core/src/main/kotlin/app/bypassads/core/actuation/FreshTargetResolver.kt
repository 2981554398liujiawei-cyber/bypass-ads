package app.bypassads.core.actuation

import app.bypassads.core.model.CtaLabels
import app.bypassads.core.model.LabelSanitizer
import app.bypassads.core.model.UiNodeSnapshot
import app.bypassads.core.model.UiSnapshot

/**
 * The single pure-Kotlin source of [FreshTargetResolution] (M2.1 hard gate).
 *
 * Pipeline: `UiSnapshot -> FreshTargetResolver -> FreshTargetResolution ->
 * ActuationGate`. The gate and the future actuator consume only resolutions
 * produced here; nothing may be hand-assembled outside this module.
 *
 * The resolver derives the resolution facts from the fresh [UiSnapshot]
 * itself (label matches, CTA presence). Generation, window token, capture
 * time and the countdown anomaly signal come from the caller/coordinator:
 * `caseGeneration` and `windowContextToken` are maintained by the case
 * lifecycle, `capturedAtElapsedMs` is the snapshot capture time, and
 * `countdownAnomaly` is the tracker's countdown sanity signal.
 */
class FreshTargetResolver {

    fun resolve(
        snapshot: UiSnapshot,
        packageName: String?,
        caseGeneration: Long,
        windowContextToken: Long,
        capturedAtElapsedMs: Long,
        fingerprint: TargetFingerprint,
        countdownAnomaly: Boolean = false,
        fastPathRuleId: String? = null,
    ): FreshTargetResolution {
        val targetPackage = snapshot.packageName ?: packageName
        val matches = snapshot.nodes
            .asSequence()
            .filter { node -> node.packageName == targetPackage && labelMatches(node, fingerprint.label) }
            .toList()
        val ctaSiblingDetected = snapshot.nodes.any { node ->
            node.packageName == targetPackage &&
                node.visibleToUser &&
                node.textOrDescription().any(CtaLabels::matches)
        }
        return FreshTargetResolution(
            packageName = targetPackage,
            currentCaseGeneration = caseGeneration,
            currentWindowContextToken = windowContextToken,
            freshCapturedAtElapsedMs = capturedAtElapsedMs,
            screenWidth = snapshot.screenWidth,
            screenHeight = snapshot.screenHeight,
            matches = matches,
            ctaSiblingDetected = ctaSiblingDetected,
            countdownAnomaly = countdownAnomaly,
            fastPathRuleId = fastPathRuleId,
        )
    }

    private fun labelMatches(node: UiNodeSnapshot, label: String): Boolean =
        node.textOrDescription().any { LabelSanitizer.sanitizeLabel(it) == label }

    private fun UiNodeSnapshot.textOrDescription(): List<String> =
        listOfNotNull(text, contentDescription).map { it.trim() }.filter { it.isNotEmpty() }
}
