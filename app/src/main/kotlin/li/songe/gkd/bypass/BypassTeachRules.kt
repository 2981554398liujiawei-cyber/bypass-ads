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
)

data class BypassTeachTestResult(val success: Boolean, val message: String)

/**
 * Owns the local teaching rules. The third-party/source bundle is never
 * edited; the effective in-memory bundle is rebuilt as source + these rules.
 */
object BypassTeachRules {
    private const val FILE_NAME = "bypass-teach-overrides.json"
    private const val MAX_TREE_DEPTH = 24
    private const val MAX_CANDIDATES = 24
    private const val MATCH_WINDOW_MS = 15_000L

    private val file: File by lazy { File(app.filesDir, FILE_NAME) }

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
        summaryOf(draft.packageName, group)
    }

    suspend fun delete(key: Int) = withContext(Dispatchers.IO) {
        val current = read()
        val apps = current.apps.mapNotNull { appRule ->
            val groups = appRule.groups.filterNot { it.key == key }
            appRule.takeIf { groups.isNotEmpty() }?.copy(groups = groups)
        }
        file.writeText(json.encodeToString(RawSubscription.serializer(), current.copy(version = current.version + 1, apps = apps)))
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

    suspend fun test(draft: BypassTeachDraft): BypassTeachTestResult {
        val current = topActivityFlow.value
        if (current.appId != draft.packageName) {
            return BypassTeachTestResult(false, "当前应用已变化，请回到目标广告界面")
        }
        if (current.activityId != draft.activityName) {
            return BypassTeachTestResult(false, "当前 Activity 已变化，请重新读取布局")
        }
        return runCatching {
            val result = A11yRuleEngine.execAction(
                GkdAction(
                    selector = draft.selector,
                    action = if (draft.coordinateX != null) "clickCenter" else "clickNode",
                    position = if (draft.coordinateX != null && draft.coordinateY != null) {
                        RawSubscription.Position(
                            left = "left + width * ${draft.coordinateX}",
                            top = "top + height * ${draft.coordinateY}",
                            right = null,
                            bottom = null,
                            x = null,
                            y = null,
                        )
                    } else null,
                ),
            )
            if (result.result) BypassTeachTestResult(true, "测试点击成功，可以保存")
            else BypassTeachTestResult(false, "测试动作未成功，请重新选择控件")
        }.getOrElse { BypassTeachTestResult(false, "测试失败：${it.message ?: "无法匹配当前布局"}") }
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
        )

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
