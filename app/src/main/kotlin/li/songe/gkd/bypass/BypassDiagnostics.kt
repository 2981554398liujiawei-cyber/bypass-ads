package li.songe.gkd.bypass

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import li.songe.gkd.META
import li.songe.gkd.app
import li.songe.gkd.a11y.topActivityFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Reasons are stable product diagnostics, rather than opaque matcher logs. */
enum class FailureReason {
    NO_RULE_FOR_APP,
    CATEGORY_DISABLED,
    MASTER_DISABLED,
    APP_DISABLED,
    GLOBAL_EXCLUDED,
    ACTIVITY_MISMATCH,
    SELECTOR_NO_MATCH,
    TARGET_FOUND_NOT_CLICKABLE,
    ACTION_FAILED,
    ACTION_NO_EFFECT,
    WAITING_FOR_DELAY,
    ACTION_TOO_EARLY,
    ACCESSIBILITY_NODE_MISSING,
    EVENT_MISSED,
    MISCLICK_SUSPECTED,
    UNKNOWN,
}

data class BypassDiagnosticEvent(
    val id: Long,
    val time: Long,
    val packageName: String,
    val activityName: String?,
    val reason: FailureReason,
    val detail: String,
)

/**
 * Self-use shadow trace layered around the existing GKD matcher (v1.0: also
 * active in the formal self-use release, not only debug). It does not scan
 * nodes independently and intentionally stores no UI text, input, or full
 * accessibility trees — only reason codes plus package/activity identity.
 * The exported package is local-only by default.
 */
object BypassDiagnostics {
    private const val MAX_EVENTS = 40
    private val _events = MutableStateFlow<List<BypassDiagnosticEvent>>(emptyList())
    val events = _events

    fun record(
        reason: FailureReason,
        packageName: String = topActivityFlow.value.appId,
        activityName: String? = topActivityFlow.value.activityId,
        detail: String = "",
    ) {
        // v1.0: the self-use release records the same privacy-safe diagnostics
        // as debug (the Diagnostics page must work on the installed release).
        val event = BypassDiagnosticEvent(
            id = System.nanoTime(),
            time = System.currentTimeMillis(),
            packageName = packageName,
            activityName = activityName,
            reason = reason,
            detail = detail.take(160).replace(Regex("[^A-Za-z0-9_ .:/=-]"), "_"),
        )
        _events.value = (listOf(event) + _events.value).take(MAX_EVENTS)
    }

    fun clear() {
        _events.value = emptyList()
    }

    fun writeLocalBundle(
        metadata: BypassRuleMetadata,
        protection: BypassRuntimeProtection,
        masterEnabled: Boolean,
    ): File {
        val current = topActivityFlow.value
        val root = JSONObject()
            .put("packageName", current.appId)
            .put("activity", current.activityId)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("ruleBundleSha256", metadata.sha256)
            .put("ruleSource", metadata.sourceType.name)
            .put("masterEnabled", masterEnabled)
            .put("accessibilityConnected", protection.accessibilityConnected)
            .put("generatedAt", System.currentTimeMillis())
        val entries = JSONArray()
        events.value.forEach { event ->
            entries.put(
                JSONObject()
                    .put("time", event.time)
                    .put("id", event.id)
                    .put("packageName", event.packageName)
                    .put("activity", event.activityName)
                    .put("failureReason", event.reason.name)
                    .put("matcherDetail", event.detail),
            )
        }
        root.put("recentMatcherEvents", entries)
        root.put("privacy", "No input text, passwords, phone/card numbers, chat content, or full accessibility tree is exported.")
        val directory = File(app.filesDir, "bypass-diagnostics").apply { mkdirs() }
        return File(directory, "diagnostic-${System.currentTimeMillis()}.json").apply {
            writeText(root.toString(2))
        }
    }
}
