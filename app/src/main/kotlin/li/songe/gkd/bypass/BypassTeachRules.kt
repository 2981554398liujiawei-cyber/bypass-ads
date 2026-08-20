package li.songe.gkd.bypass

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.app
import li.songe.gkd.data.GkdAction
import li.songe.gkd.data.RawSubscription
import li.songe.gkd.util.json
import java.io.File

data class BypassTeachDraft(
    val packageName: String,
    val activityName: String,
    val selector: String,
    val coordinateX: Float? = null,
    val coordinateY: Float? = null,
)

data class BypassTeachCandidate(
    val selector: String,
    val label: String,
    val detail: String,
)

data class BypassTeachRuleSummary(
    val key: Int,
    val packageName: String,
    val activityName: String,
    val selector: String,
    val coordinateFallback: Boolean,
    val verification: BypassTeachVerification,
)

data class BypassTeachTestResult(val success: Boolean, val message: String)

enum class BypassTeachVerification {
    PENDING_VERIFICATION,
    VERIFIED,
    TEST_FAILED,
}

/**
 * Owns the local teaching rules. The third-party/source bundle is never
 * edited; the effective in-memory bundle is rebuilt as source + these rules.
 */
object BypassTeachRules {
    private const val FILE_NAME = "bypass-teach-overrides.json"
    private const val VERIFICATION_PREFS = "bypass_teach_verification"
    private const val RETEST_PREFS = "bypass_teach_retest"
    private const val MAX_TREE_DEPTH = 24
    private const val MAX_CANDIDATES = 24
    private const val MATCH_WINDOW_MS = 15_000L
    private const val VERIFICATION_WINDOW_MS = 60_000L

    private val file: File by lazy { File(app.filesDir, FILE_NAME) }
    private val verificationPrefs by lazy { app.getSharedPreferences(VERIFICATION_PREFS, 0) }
    private val retestPrefs by lazy { app.getSharedPreferences(RETEST_PREFS, 0) }

    /**
     * RetestController state: an armed verification waits for the target
     * package/activity to reappear, then runs ONE armed test; the verdict is
     * written back by the engine via OutcomeVerifier (SUCCESS_CONFIRMED ->
     * VERIFIED) or by window expiry (TEST_FAILED). Never by a first
     * SELECTOR_NO_MATCH.
     */
    data class ArmedVerification(
        val packageName: String,
        val activityName: String,
        val selector: String,
        val coordinate: Boolean,
        val armedAt: Long,
    )

    /** ARM (微信/支付宝禁止瞎 deep link: 用户手动返回小程序). */
    fun armVerification(draft: BypassTeachDraft): Boolean {
        val now = System.currentTimeMillis()
        val coordinate = draft.coordinateX != null && draft.coordinateY != null
        return retestPrefs.edit()
            .putString("packageName", draft.packageName)
            .putString("activityName", draft.activityName)
            .putString("selector", draft.selector)
            .putBoolean("coordinate", coordinate)
            .putLong("armedAt", now)
            .commit()
    }

    fun readArmedVerification(): ArmedVerification? {
        val packageName = retestPrefs.getString("packageName", null) ?: return null
        val armedAt = retestPrefs.getLong("armedAt", 0L)
        if (armedAt <= 0L) return null
        return ArmedVerification(
            packageName = packageName,
            activityName = retestPrefs.getString("activityName", "").orEmpty(),
            selector = retestPrefs.getString("selector", "").orEmpty(),
            coordinate = retestPrefs.getBoolean("coordinate", false),
            armedAt = armedAt,
        )
    }

    fun clearArmedVerification() {
        retestPrefs.edit().clear().apply()
    }

    /** Whether the armed verification window has expired. */
    fun armedWindowExpired(armedAt: Long): Boolean =
        System.currentTimeMillis() - armedAt > VERIFICATION_WINDOW_MS

    /**
     * A pending/armed rule saw a no-match (or failed action). Do NOT fail
     * the verification on the first miss: only when the armed window is over
     * without a SUCCESS_CONFIRMED does it become TEST_FAILED.
     */
    fun noticeNoMatch(packageName: String?, groupKey: Int) {
        val armed = readArmedVerification() ?: return
        if (armed.packageName != packageName) return
        if (!armedWindowExpired(armed.armedAt)) return
        markVerification(packageName, groupKey, success = false)
        clearArmedVerification()
    }

    /** Expire armed verifications whose window ended without success. */
    fun checkVerificationWindows() {
        val armed = readArmedVerification() ?: return
        if (!armedWindowExpired(armed.armedAt)) return
        // Find the matching saved group and mark it failed.
        val key = savedKeyFor(armed.packageName, armed.selector)
        if (key != null) {
            markVerification(armed.packageName, key, success = false)
        }
        clearArmedVerification()
    }

    private fun savedKeyFor(packageName: String, selector: String): Int? = runCatching {
        if (!file.exists()) return@runCatching null
        val current = RawSubscription.parse(file.readText(), json5 = false)
        current.apps.firstOrNull { it.id == packageName }
            ?.groups?.firstOrNull { g -> g.rules.firstOrNull()?.matches?.firstOrNull() == selector }
            ?.key
    }.getOrNull()

    private fun emptySubscription() = RawSubscription(
        id = BYPASS_SPLASH_SUBS_ID,
        name = "Bypass Ads 本地教学规则",
        version = 1,
    )

    suspend fun read(): RawSubscription = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptySubscription()
        runCatching {
            RawSubscription.parse(file.readText(), json5 = false)
        }.getOrElse { emptySubscription() }
    }

    suspend fun save(draft: BypassTeachDraft): BypassTeachRuleSummary = withContext(Dispatchers.IO) {
        require(draft.packageName.isNotBlank()) { "缺少应用包名" }
        require(draft.activityName.isNotBlank()) { "缺少 Activity" }
        require(draft.selector.isNotBlank()) { "缺少选择器" }
        val current = read()
        val group = buildGroup(draft, nextKey(current))
        val apps = current.apps.toMutableList()
        val index = apps.indexOfFirst { it.id == draft.packageName }
        val appRule = apps.getOrNull(index) ?: RawSubscription.RawApp(
            id = draft.packageName,
            name = null,
            groups = emptyList(),
        )
        val nextApp = appRule.copy(groups = appRule.groups.filterNot {
            it.activityIds?.contains(draft.activityName) == true &&
                it.rules.firstOrNull()?.matches?.firstOrNull() == draft.selector
        } + group)
        if (index >= 0) apps[index] = nextApp else apps += nextApp
        val updated = current.copy(version = current.version + 1, apps = apps)
        file.writeText(json.encodeToString(RawSubscription.serializer(), updated))
        verificationPrefs.edit().putString(
            verificationKey(draft.packageName, group.key),
            BypassTeachVerification.PENDING_VERIFICATION.name,
        ).apply()
        summaryOf(draft.packageName, group)
    }

    suspend fun delete(key: Int) = withContext(Dispatchers.IO) {
        val current = read()
        val removedPackages = current.apps.flatMap { appRule ->
            appRule.groups.filter { it.key == key }.map { appRule.id }
        }
        val apps = current.apps.mapNotNull { appRule ->
            val groups = appRule.groups.filterNot { it.key == key }
            appRule.takeIf { groups.isNotEmpty() }?.copy(groups = groups)
        }
        file.writeText(json.encodeToString(RawSubscription.serializer(), current.copy(version = current.version + 1, apps = apps)))
        verificationPrefs.edit().apply {
            removedPackages.forEach { remove(verificationKey(it, key)) }
        }.apply()
    }

    suspend fun list(): List<BypassTeachRuleSummary> = withContext(Dispatchers.IO) {
        read().apps.flatMap { appRule -> appRule.groups.map { summaryOf(appRule.id, it) } }
    }

    suspend fun export(): File = withContext(Dispatchers.IO) {
        val target = File(app.filesDir, "bypass-teach-export-${System.currentTimeMillis()}.json")
        target.writeText(json.encodeToString(RawSubscription.serializer(), read()))
        target
    }

    fun merge(base: RawSubscription, overrides: RawSubscription): RawSubscription {
        if (overrides.apps.isEmpty()) return base
        val overrideByApp = overrides.apps.associateBy { it.id }
        val apps = base.apps.map { appRule ->
            val local = overrideByApp[appRule.id] ?: return@map appRule
            val localKeys = local.groups.map { it.key }.toSet()
            appRule.copy(groups = appRule.groups.filterNot { it.key in localKeys } + local.groups)
        }.toMutableList()
        overrideByApp.forEach { (packageName, local) ->
            if (apps.none { it.id == packageName }) apps += local
        }
        return base.copy(apps = apps)
    }

    suspend fun apply(base: RawSubscription): RawSubscription = merge(base, read())

    fun isPendingVerification(packageName: String?, groupKey: Int): Boolean {
        if (packageName.isNullOrBlank()) return false
        return verificationFor(packageName, groupKey) == BypassTeachVerification.PENDING_VERIFICATION
    }

    fun markVerification(packageName: String?, groupKey: Int, success: Boolean) {
        if (!isPendingVerification(packageName, groupKey)) return
        verificationPrefs.edit().putString(
            verificationKey(packageName!!, groupKey),
            if (success) BypassTeachVerification.VERIFIED.name else BypassTeachVerification.TEST_FAILED.name,
        ).apply()
        if (success) {
            // VERIFIED only via OutcomeVerifier SUCCESS_CONFIRMED; clear any
            // armed retest so a later window expiry cannot overwrite it.
            clearArmedVerification()
        }
    }

    fun currentCandidates(): List<BypassTeachCandidate> {
        val service = A11yRuleEngine.service ?: return emptyList()
        val root = runCatching { service.windowNodeInfo }.getOrNull() ?: return emptyList()
        val candidates = linkedMapOf<String, BypassTeachCandidate>()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_TREE_DEPTH || candidates.size >= MAX_CANDIDATES) return
            val target = clickableAncestor(node) ?: node
            selectorFor(target)?.let { selector ->
                val hint = target.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: target.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: target.viewIdResourceName?.takeIf { it.isNotBlank() }
                    ?: target.className?.toString()?.substringAfterLast('.')
                    ?: "候选控件"
                val detail = if (target === node) {
                    if (node.isClickable) "可点击控件" else "无可点击父级，需谨慎"
                } else {
                    "已提升到可点击父级"
                }
                candidates.putIfAbsent(selector, BypassTeachCandidate(selector, hint.take(40), detail))
            }
            repeat(node.childCount.coerceAtMost(80)) { index ->
                runCatching { node.getChild(index) }.getOrNull()?.let { visit(it, depth + 1) }
            }
        }

        visit(root, 0)
        return candidates.values.toList()
    }

    /**
     * ARM a verification (RetestController). The rule must already be saved;
     * this only records the target and waits for the package/activity to
     * reappear. The engine runs one armed test and writes the verdict back
     * (SUCCESS_CONFIRMED -> VERIFIED, window expiry -> TEST_FAILED). A first
     * SELECTOR_NO_MATCH is never a failure by itself.
     */
    suspend fun test(draft: BypassTeachDraft): BypassTeachTestResult {
        val current = topActivityFlow.value
        if (draft.packageName.isBlank()) {
            return BypassTeachTestResult(false, "缺少目标应用")
        }
        if (draft.selector.isBlank()) {
            return BypassTeachTestResult(false, "缺少选择器，请先选择控件")
        }
        val saved = list().any {
            it.packageName == draft.packageName && it.selector == draft.selector
        }
        if (!saved) {
            return BypassTeachTestResult(false, "请先保存为待验证，再开始验证")
        }
        armVerification(draft)
        val instruction = if (current.appId == draft.packageName) {
            "验证已开始：请稍候，验证窗口 60 秒"
        } else {
            "验证已开始：请返回 ${draft.packageName}，验证窗口 60 秒"
        }
        return BypassTeachTestResult(true, instruction)
    }

    fun coordinateDraft(packageName: String, activityName: String, x: Float, y: Float) =
        BypassTeachDraft(
            packageName = packageName,
            activityName = activityName,
            selector = "[id=\"android:id/content\"]",
            coordinateX = x.coerceIn(0.01f, 0.99f),
            coordinateY = y.coerceIn(0.01f, 0.99f),
        )

    private fun nextKey(subscription: RawSubscription): Int {
        val key = subscription.apps.flatMap { it.groups }.minOfOrNull { it.key } ?: 0
        return if (key <= -1_000_000) key - 1 else -1_000_000 - subscription.apps.sumOf { it.groups.size }
    }

    private fun buildGroup(draft: BypassTeachDraft, key: Int): RawSubscription.RawAppGroup {
        val coordinate = draft.coordinateX != null && draft.coordinateY != null
        val rule = RawSubscription.RawAppRule(
            key = 1,
            name = "教学点击",
            preKeys = null,
            action = if (coordinate) "clickCenter" else "clickNode",
            position = if (coordinate) {
                RawSubscription.Position(
                    left = "left + width * ${draft.coordinateX}",
                    top = "top + height * ${draft.coordinateY}",
                    right = null,
                    bottom = null,
                    x = null,
                    y = null,
                )
            } else null,
            matches = listOf(draft.selector),
            excludeMatches = null,
            excludeAllMatches = null,
            anyMatches = null,
            actionCdKey = null,
            actionMaximumKey = null,
            actionCd = null,
            actionDelay = null,
            fastQuery = false,
            matchRoot = coordinate,
            actionMaximum = 1,
            order = -1_000_000,
            forcedTime = null,
            priorityTime = null,
            priorityActionMaximum = null,
            matchDelay = null,
            matchTime = MATCH_WINDOW_MS,
            resetMatch = null,
            snapshotUrls = null,
            excludeSnapshotUrls = null,
            exampleUrls = null,
            activityIds = listOf(draft.activityName),
            excludeActivityIds = null,
            versionCode = null,
            versionName = null,
            swipeArg = null,
        )
        return RawSubscription.RawAppGroup(
            key = key,
            name = "教学规则 · 跳过",
            desc = if (coordinate) "仅当前应用、Activity，启动后 15 秒内，单次相对坐标动作" else "仅当前应用与 Activity 的本地控件规则",
            enable = true,
            scopeKeys = null,
            actionCdKey = null,
            actionMaximumKey = null,
            actionCd = null,
            actionDelay = null,
            fastQuery = false,
            matchRoot = coordinate,
            actionMaximum = 1,
            priorityTime = null,
            priorityActionMaximum = null,
            order = -1_000_000,
            forcedTime = null,
            matchDelay = null,
            matchTime = MATCH_WINDOW_MS,
            resetMatch = null,
            snapshotUrls = null,
            excludeSnapshotUrls = null,
            exampleUrls = null,
            activityIds = listOf(draft.activityName),
            excludeActivityIds = null,
            rules = listOf(rule),
            versionCode = null,
            versionName = null,
            ignoreGlobalGroupMatch = true,
        )
    }

    private fun summaryOf(packageName: String, group: RawSubscription.RawAppGroup) =
        BypassTeachRuleSummary(
            key = group.key,
            packageName = packageName,
            activityName = group.activityIds?.firstOrNull().orEmpty(),
            selector = group.rules.firstOrNull()?.matches?.firstOrNull().orEmpty(),
            coordinateFallback = group.rules.firstOrNull()?.action == "clickCenter",
            verification = verificationFor(packageName, group.key),
        )

    private fun verificationFor(packageName: String, groupKey: Int): BypassTeachVerification = runCatching {
        BypassTeachVerification.valueOf(
            verificationPrefs.getString(
                verificationKey(packageName, groupKey),
                BypassTeachVerification.PENDING_VERIFICATION.name,
            )!!,
        )
    }.getOrDefault(BypassTeachVerification.PENDING_VERIFICATION)

    private fun verificationKey(packageName: String, groupKey: Int) = "$packageName:$groupKey"

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            if (current?.isClickable == true) return current
            current = runCatching { current?.parent }.getOrNull()
        }
        return null
    }

    private fun selectorFor(node: AccessibilityNodeInfo): String? {
        node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { return "[vid=\"${escape(it)}\"]" }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return "[desc=\"${escape(it)}\"]" }
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return "[text=\"${escape(it)}\"]" }
        val simpleClass = node.className?.toString()?.substringAfterLast('.')?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
        return simpleClass?.let { "@$it" }
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
