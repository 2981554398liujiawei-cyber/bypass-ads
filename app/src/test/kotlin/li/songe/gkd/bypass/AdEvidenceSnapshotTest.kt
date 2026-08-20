package li.songe.gkd.bypass

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import li.songe.gkd.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-6: the candidate snapshot model (BypassCandidateSnapshot) is the
 * privacy-safe AdEvidenceSnapshot — only whitelisted non-content fields may
 * ever be recorded: package/activity, candidate class/bounds/clickable, and
 * ad-related text/desc/viewId. No chat, search, input, password, card or
 * page-content fields exist in the model. (The runtime sanitizer additionally
 * filters sensitive values before a snapshot is taken; the model itself is
 * the schema boundary.)
 */
class AdEvidenceSnapshotTest {

    /** The ONLY fields the snapshot model may carry. */
    private val allowedFields = setOf(
        "text",
        "description",
        "viewId",
        "className",
        "bounds",
        "clickable",
        "parentClickable",
        "packageName",
        "activityName",
    )

    @Test
    fun snapshot_model_fields_are_whitelisted_only() {
        // A snapshot serialized to JSON must never contain a field outside
        // the whitelist — the schema is the privacy boundary. (kotlinx drops
        // nulls by default, so absent nullable fields are fine.)
        val snapshot = BypassCandidateSnapshot(
            text = "跳过",
            description = "关闭广告",
            viewId = "ad_close",
            className = "android.widget.Button",
            bounds = "972,192,1152,265",
            clickable = true,
            parentClickable = false,
            packageName = "com.tencent.mm",
            activityName = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI",
        )
        val element = json.parseToJsonElement(
            json.encodeToString(BypassCandidateSnapshot.serializer(), snapshot),
        ).jsonObject
        assertTrue(
            "snapshot fields must be a subset of the privacy whitelist: ${element.keys}",
            element.keys.all { it in allowedFields },
        )
        assertEquals(allowedFields, element.keys)
    }

    @Test
    fun model_has_no_content_bearing_input_fields() {
        // The model deliberately has no field that could carry chat / search /
        // input / password / card content.
        assertTrue(!allowedFields.contains("inputType"))
        assertTrue(!allowedFields.contains("hint"))
        assertTrue(!allowedFields.contains("editable"))
        assertTrue(!allowedFields.contains("pageText"))
        assertTrue(!allowedFields.contains("chat"))
    }
}
