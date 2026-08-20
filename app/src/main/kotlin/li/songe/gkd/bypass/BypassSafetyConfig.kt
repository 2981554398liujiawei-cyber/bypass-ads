package li.songe.gkd.bypass

import li.songe.gkd.app
import org.json.JSONObject

/**
 * Runtime high-risk package list. The single source is
 * rules/safety_exclusions.json (copied to assets by the bundle generator);
 * the built-in set is only a fallback so the gate never silently widens.
 */
object BypassSafetyConfig {

    private val builtinHighRisk = setOf(
        "com.eg.android.AlipayGphone",
        "com.tencent.mm",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.android.systemui",
        "com.miui.securitycenter",
        "com.miui.securitycore",
        "com.miui.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "com.oneplus.bpkb",
        "com.authenticator.auth",
        "com.chinamworld.main",
        "com.android.bankabc",
        "com.ccb.life",
        "com.unionpay",
        "com.pingan.pinganwifi",
        "com.eg.android.bankpay",
    )

    private val highRisk: Set<String> by lazy {
        runCatching {
            val text = app.assets.open("bypass_safety_exclusions.json").bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val arr = json.optJSONArray("high_risk_packages") ?: return@runCatching builtinHighRisk
            (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) }
        }.getOrDefault(builtinHighRisk)
    }

    fun isHighRisk(packageName: String?): Boolean = packageName != null && highRisk.contains(packageName)

    /** Test hook: replace the resolved set (JVM tests). */
    @Volatile
    var testOverride: Set<String>? = null

    fun isHighRiskTestable(packageName: String?): Boolean =
        packageName != null && (testOverride ?: highRisk).contains(packageName)
}
