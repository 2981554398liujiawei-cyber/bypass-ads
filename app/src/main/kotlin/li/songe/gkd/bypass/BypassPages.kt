package li.songe.gkd.bypass

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import li.songe.gkd.BYPASS_SPLASH_SUBS_ID
import li.songe.gkd.MainActivity
import li.songe.gkd.META
import li.songe.gkd.service.HttpService
import li.songe.gkd.ui.A11YScopeAppListRoute
import li.songe.gkd.ui.A11yEventLogRoute
import li.songe.gkd.ui.ActionLogRoute
import li.songe.gkd.ui.ActivityLogRoute
import li.songe.gkd.ui.AdvancedPageRoute
import li.songe.gkd.ui.AppOpsAllowRoute
import li.songe.gkd.ui.AuthA11yRoute
import li.songe.gkd.ui.BlockA11yAppListRoute
import li.songe.gkd.ui.CrashReportRoute
import li.songe.gkd.ui.SlowGroupRoute
import li.songe.gkd.ui.SnapshotPageRoute
import li.songe.gkd.ui.SubsAppListRoute
import li.songe.gkd.ui.SubsCategoryRoute
import li.songe.gkd.ui.SubsGlobalGroupListRoute
import li.songe.gkd.util.BackupUtils
import li.songe.gkd.util.appIconMapFlow
import li.songe.gkd.util.saveFileToDownloads
import li.songe.gkd.util.shareFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBypassTime(epochMs: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

private val BypassAdCategory.title: String
    get() = when (this) {
        BypassAdCategory.SPLASH -> "开屏广告"
        BypassAdCategory.IN_APP_FULLSCREEN -> "全屏 / 插屏广告"
        BypassAdCategory.MARKETING_POPUP -> "营销弹窗"
        BypassAdCategory.OTHER_CLOSABLE -> "其它可关闭广告"
    }

@Composable
private fun Drawable.toBypassImage() = remember(this) { toBitmap().asImageBitmap() }

@Composable
fun BypassHomePage(engine: BypassEngine, onOpenRecords: () -> Unit) {
    val context = LocalContext.current
    val state by engine.serviceState.collectAsState()
    val master by engine.masterEnabled.collectAsState()
    val stats by engine.stats.collectAsState()
    val skipped by engine.skipCount.collectAsState()
    val latest by engine.latestAction.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassStatusCard(state.label, state.description, state.status == BypassServiceStatus.NORMAL, if (state.status == BypassServiceStatus.NORMAL) null else "开启无障碍") {
            engine.requestServiceRecovery()
            context.openBypassSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("自动跳过广告") {
            BypassSwitchRow("自动跳过广告", if (master) "所有已启用的广告规则正在运行" else "已暂停所有广告规则", master, engine::setMasterEnabled)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("数据概览") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BypassMetric("已启用规则", stats.ruleCount.toString(), Modifier.weight(1f))
                BypassMetric("已保护应用", "${stats.appCount} 个", Modifier.weight(1f))
                BypassMetric("累计跳过", skipped.toString(), Modifier.weight(1f))
            }
            BypassMutedText("${stats.groupCount} 个规则组正在提供保护", 12)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("最近触发") {
            if (latest == null) {
                BypassMutedText("还没有触发记录。打开带广告的应用后，这里会显示最近一次结果。", 13)
            } else {
                Text(latest!!.appName ?: latest!!.appId, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
                BypassMutedText("${latest!!.groupName ?: "广告规则"} · ${formatBypassTime(latest!!.time)}", 12)
            }
            Spacer(Modifier.height(10.dp))
            BypassModeButton("查看记录", false, onOpenRecords)
        }
    }
}

@Composable
fun BypassAdBlockingPage(engine: BypassEngine, onOpenRules: () -> Unit, onOpenAdvancedRules: () -> Unit) {
    val categories by engine.adCategories.collectAsState()
    val splash = categories.firstOrNull { it.category == BypassAdCategory.SPLASH }
    val inApp = categories.filter { it.category != BypassAdCategory.SPLASH }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        splash?.let {
            BypassSectionCard("开屏广告") { BypassCategoryRow(it, engine::setAdCategoryEnabled) }
            Spacer(Modifier.height(14.dp))
        }
        BypassSectionCard("应用内广告") {
            inApp.forEachIndexed { index, category ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                BypassCategoryRow(category, engine::setAdCategoryEnabled)
            }
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("规则与订阅") {
            BypassNavRow("规则包管理", onOpenRules)
            Spacer(Modifier.height(8.dp))
            BypassNavRow("高级规则管理", onOpenAdvancedRules)
        }
    }
}

@Composable
private fun BypassCategoryRow(state: BypassAdCategoryState, onChange: (BypassAdCategory, Boolean) -> Unit) {
    BypassSwitchRow(
        title = state.title,
        subtitle = "${state.description}\n${state.appCount} 个应用 · ${state.groupCount} 组 · ${state.ruleCount} 条规则",
        checked = state.enabled,
        onCheckedChange = { onChange(state.category, it) },
    )
}

@Composable
fun BypassAppsPage(engine: BypassEngine, onOpenApp: (String) -> Unit) {
    var apps by remember { mutableStateOf(emptyList<BypassAppInfo>()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var enabledOnly by remember { mutableStateOf(false) }
    var sortByRules by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val icons by appIconMapFlow.collectAsState()
    LaunchedEffect(refreshKey) {
        loading = true
        apps = engine.getProtectedApps()
        loading = false
    }
    if (loading) return BypassMutedText("正在加载应用列表…")
    val shown = apps.asSequence()
        .filter { !enabledOnly || it.enabled }
        .filter { it.appName.contains(query, true) || it.packageName.contains(query, true) }
        .let { if (sortByRules) it.sortedByDescending { app -> app.ruleCount } else it.sortedBy { app -> app.appName } }
        .toList()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索应用或包名") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BypassModeButton(if (enabledOnly) "仅显示已启用" else "显示全部", enabledOnly, { enabledOnly = !enabledOnly }, Modifier.weight(1f))
            BypassModeButton(if (sortByRules) "按规则数量" else "按应用名称", sortByRules, { sortByRules = !sortByRules }, Modifier.weight(1f))
            BypassModeButton("刷新", false, { refreshKey++ })
        }
        BypassMutedText("关闭某个应用会将它加入本机白名单，不影响其它应用。", 12)
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.packageName }) { entry ->
                val icon = icons[entry.packageName]?.toBypassImage()
                BypassAppRow(entry.appName, entry.packageName, icon, entry.enabled, "${entry.groupCount} 组 · ${entry.ruleCount} 条规则", { onOpenApp(entry.packageName) }) { enabled ->
                    scope.launch { engine.setAppEnabled(entry.packageName, enabled) }
                    apps = apps.map { if (it.packageName == entry.packageName) it.copy(enabled = enabled) else it }
                }
            }
        }
    }
}

@Composable
fun BypassAppDetailPage(engine: BypassEngine, packageName: String, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<BypassAppDetail?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(packageName) { detail = engine.getAppDetail(packageName) }
    val item = detail ?: return BypassMutedText("正在加载应用规则…")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton(onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard(item.appName) {
            BypassMutedText(item.packageName, 12)
            item.versionName?.let { BypassMutedText("版本 $it", 12) }
            Spacer(Modifier.height(8.dp))
            BypassSwitchRow(
                "为此应用启用保护",
                "关闭后此应用进入白名单。",
                item.enabled,
                onCheckedChange = { enabled ->
                scope.launch { engine.setAppEnabled(item.packageName, enabled); detail = engine.getAppDetail(packageName) }
                },
            )
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("规则组") {
            item.groups.forEachIndexed { index, group ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                BypassSwitchRow(
                    group.name,
                    "${group.category.title} · ${group.ruleCount} 条规则",
                    group.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch { engine.setRuleGroupEnabled(item.packageName, group.key, enabled); detail = engine.getAppDetail(packageName) }
                    },
                )
            }
        }
    }
}

@Composable
fun BypassTriggerLogPage(engine: BypassEngine, onBack: () -> Unit) {
    val recent by engine.recentActions.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        BypassBackButton(onBack)
        Spacer(Modifier.height(10.dp))
        if (recent.isEmpty()) {
            BypassMutedText("还没有跳过记录。打开带广告的应用后，这里会显示记录。")
            return@Column
        }
        BypassModeButton("清除记录", false, onClick = { scope.launch { engine.clearRecentActions() } })
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(recent, key = { it.time }) { action ->
                Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(action.appName ?: action.appId, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
                        Text(action.groupName?.let { "已跳过 · $it" } ?: "已跳过广告", Modifier.padding(top = 3.dp), fontSize = 12.sp, color = BypassPalette.Muted)
                    }
                    Text(formatBypassTime(action.time), fontSize = 12.sp, color = BypassPalette.Faint)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun BypassSettingsPage(engine: BypassEngine, onOpenService: () -> Unit, onOpenPreferences: () -> Unit, onOpenBackup: () -> Unit, onOpenRecords: () -> Unit, onOpenAdvanced: () -> Unit, onOpenFullTools: () -> Unit, onOpenAbout: () -> Unit, onOpenLicenses: () -> Unit) {
    val notificationEnabled by engine.persistentNotificationEnabled.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassSectionCard("运行与提示") {
            BypassNavRow("服务与权限", onOpenService)
            Spacer(Modifier.height(8.dp))
            BypassSwitchRow("常驻通知", "显示服务运行状态，帮助系统保持后台服务。", notificationEnabled, engine::setPersistentNotificationEnabled)
            Spacer(Modifier.height(8.dp))
            BypassNavRow("提示与高级设置", onOpenPreferences)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("数据与工具") {
            BypassNavRow("备份与恢复", onOpenBackup)
            Spacer(Modifier.height(8.dp))
            BypassNavRow("记录与日志", onOpenRecords)
            Spacer(Modifier.height(8.dp))
            BypassNavRow("高级工具", onOpenAdvanced)
            if (META.channel == "fulltools") {
                Spacer(Modifier.height(8.dp))
                BypassNavRow("完整工具与网络", onOpenFullTools)
            }
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("关于") {
            BypassNavRow("关于 Bypass Ads", onOpenAbout)
            Spacer(Modifier.height(8.dp))
            BypassNavRow("开源许可", onOpenLicenses)
        }
    }
}

@Composable
fun BypassServicePermissionsPage(engine: BypassEngine, onBack: () -> Unit) {
    val context = LocalContext.current
    val service by engine.serviceState.collectAsState()
    val permissions by engine.permissionState.collectAsState()
    LaunchedEffect(Unit) { engine.refreshPermissionState() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton(onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("无障碍服务") {
            BypassStateLine(service.label, service.status == BypassServiceStatus.NORMAL)
            Spacer(Modifier.height(10.dp))
            BypassModeButton(if (service.status == BypassServiceStatus.NEED_AUTHORIZATION) "开启无障碍" else "管理无障碍服务", false, onClick = {
                engine.requestServiceRecovery(); context.openBypassSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("通知权限") {
            BypassStateLine(if (permissions.notificationGranted) "已允许" else "未允许", permissions.notificationGranted)
            Spacer(Modifier.height(10.dp))
            BypassModeButton("管理", false, onClick = { context.openNotificationSettings() })
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("后台运行") {
            BypassStateLine(if (permissions.ignoringBatteryOptimizations) "未受电池优化限制" else "可能限制后台运行", permissions.ignoringBatteryOptimizations)
            Spacer(Modifier.height(10.dp))
            BypassModeButton("电池优化设置", false, onClick = { context.openBatterySettings() })
            Spacer(Modifier.height(8.dp))
            BypassModeButton("应用详情", false, onClick = { context.openAppDetails() })
        }
    }
}

@Composable
fun BypassAdvancedSettingsPage(engine: BypassEngine, onBack: () -> Unit) {
    val context = LocalContext.current
    val fallback by engine.genericFallbackEnabled.collectAsState()
    val toastEnabled by engine.actionToastEnabled.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton(onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("跳过策略") {
            BypassSwitchRow("通用开屏保护", "在无专用规则时使用受限的跳过识别。", fallback, engine::setGenericFallbackEnabled)
            Spacer(Modifier.height(6.dp))
            BypassSwitchRow("跳过成功提示", if (toastEnabled) "显示 ✨Bypass Ads✨" else "静默跳过", toastEnabled, engine::setActionToastEnabled)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("服务自动恢复") {
            BypassMutedText("服务授权仍在但实例掉线时，可使用现有本机能力重新检查。", 12)
            Spacer(Modifier.height(10.dp))
            BypassModeButton("立即检查", false, engine::requestServiceRecovery)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("后台运行优化") { BypassModeButton("管理电池优化", false, onClick = { context.openBatterySettings() }) }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("规则与记录") {
            BypassModeButton("恢复内置广告规则", false, onClick = { scope.launch { engine.restoreBundledRules() } })
            Spacer(Modifier.height(8.dp))
            BypassModeButton("清空触发记录", false, onClick = { scope.launch { engine.clearRecentActions() } })
        }
    }
}

@Composable
fun BypassRulesPage(engine: BypassEngine, onOpenApps: () -> Unit, onOpenDetail: () -> Unit, onOpenAdvanced: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val metadata by engine.ruleMetadata.collectAsState()
    val categories by engine.adCategories.collectAsState()
    var result by remember { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            result = if (text == null) "无法读取所选文件" else engine.importLocalRules(text, uri.lastPathSegment?.substringAfterLast('/')).message
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassSectionCard("当前广告规则包") {
            Text("Bypass Ads 广告规则", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
            Spacer(Modifier.height(6.dp))
            BypassStateLine("正在使用", true)
            Spacer(Modifier.height(10.dp))
            BypassMutedText("来源：${if (metadata.sourceType == BypassRuleSourceType.BUNDLED) "内置规则" else "本地导入"}\n规则版本：${metadata.bundleVersion?.toString() ?: "未标记版本"}\n覆盖应用：${metadata.appCount}\n专用规则组：${metadata.groupCount}\n规则：${metadata.ruleCount}\n更新时间：${metadata.installedAt.takeIf { it > 0 }?.let(::formatBypassTime) ?: "尚未加载"}", 13, 20)
            metadata.sourceFileName?.let { BypassMutedText("文件：$it", 12) }
            Spacer(Modifier.height(10.dp))
            BypassModeButton("查看覆盖应用", false, onOpenDetail)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("分类覆盖") {
            categories.forEach { state ->
                BypassMutedText("${state.title} · ${state.appCount} 个应用 · ${state.ruleCount} 条规则", 13)
                Spacer(Modifier.height(5.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("规则管理") {
            BypassModeButton("查看应用控制", false, onOpenApps)
            Spacer(Modifier.height(8.dp))
            BypassModeButton("导入本地规则", false, onClick = { choose.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
            Spacer(Modifier.height(8.dp))
            BypassModeButton("恢复内置规则", false, onClick = { scope.launch { result = engine.restoreBundledRules().message } })
            Spacer(Modifier.height(8.dp))
            BypassNavRow("进入高级规则管理", onOpenAdvanced)
            result?.let { BypassMutedText(it, 12) }
        }
    }
}

@Composable
fun BypassRuleDetailPage(engine: BypassEngine, onBack: () -> Unit, onOpenApp: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(emptyList<BypassAppInfo>()) }
    val metadata by engine.ruleMetadata.collectAsState()
    LaunchedEffect(metadata.sha256) { apps = engine.getProtectedApps().sortedBy { it.appName } }
    val filtered = apps.filter { it.appName.contains(query, true) || it.packageName.contains(query, true) }
    Column(Modifier.fillMaxSize()) {
        BypassBackButton(onBack)
        Spacer(Modifier.height(12.dp))
        BypassMutedText("版本 ${metadata.bundleVersion ?: "未标记"} · ${metadata.appCount} 个应用 · ${metadata.ruleCount} 条规则", 12)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索已保护应用") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.packageName }) { entry ->
                Row(Modifier.fillMaxWidth().clickable { onOpenApp(entry.packageName) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.appName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
                        Text(entry.packageName, fontSize = 11.sp, color = BypassPalette.Faint)
                    }
                    BypassMutedText("${entry.groupCount} 组", 12)
                }
            }
        }
    }
}

@Composable
fun BypassBackupPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { BackupUtils.importBackUpData(uri) }.onSuccess { result = "备份已导入" }.onFailure { result = "导入失败：${it.message ?: "无法读取备份"}" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton(onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("备份与恢复") {
            BypassMutedText("备份包含应用开关、分类开关、规则包配置、本地规则和高级设置。", 12)
            Spacer(Modifier.height(10.dp))
            BypassModeButton("导入备份", false, onClick = { importLauncher.launch(arrayOf("application/zip")) })
            Spacer(Modifier.height(8.dp))
            BypassModeButton("分享备份", false, onClick = { scope.launch(Dispatchers.IO) { runCatching { activity.shareFile(BackupUtils.exportBackUpData(), "分享 Bypass Ads 备份") }.onSuccess { result = "已打开分享面板" }.onFailure { result = "导出失败：${it.message ?: "未知错误"}" } } })
            Spacer(Modifier.height(8.dp))
            BypassModeButton("保存到下载", false, onClick = { scope.launch(Dispatchers.IO) { runCatching { activity.saveFileToDownloads(BackupUtils.exportBackUpData()) }.onSuccess { result = "已保存到下载目录" }.onFailure { result = "保存失败：${it.message ?: "未知错误"}" } } })
            result?.let { BypassMutedText(it, 12) }
        }
    }
}

@Composable
fun BypassAdvancedToolsPage(onBack: () -> Unit, onOpenRoute: (NavKey) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton(onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("运行模式与权限") {
            BypassNavRow("无障碍授权", { onOpenRoute(AuthA11yRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("无障碍作用范围", { onOpenRoute(A11YScopeAppListRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("应用白名单与黑名单", { onOpenRoute(BlockA11yAppListRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("AppOps 与受限设置", { onOpenRoute(AppOpsAllowRoute) })
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("规则工具") {
            BypassNavRow("应用规则", { onOpenRoute(SubsAppListRoute(BYPASS_SPLASH_SUBS_ID)) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("全局规则", { onOpenRoute(SubsGlobalGroupListRoute(BYPASS_SPLASH_SUBS_ID)) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("规则类别", { onOpenRoute(SubsCategoryRoute(BYPASS_SPLASH_SUBS_ID)) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("慢规则", { onOpenRoute(SlowGroupRoute) })
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("界面诊断与日志") {
            BypassNavRow("快照与截图", { onOpenRoute(SnapshotPageRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("Activity 日志", { onOpenRoute(ActivityLogRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("无障碍事件日志", { onOpenRoute(A11yEventLogRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("动作记录", { onOpenRoute(ActionLogRoute()) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("崩溃报告", { onOpenRoute(CrashReportRoute) }); Spacer(Modifier.height(8.dp))
            BypassNavRow("高级调试设置", { onOpenRoute(AdvancedPageRoute) })
        }
    }
}

@Composable
fun BypassFullToolsPage(
    onBack: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenHelp: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val running by HttpService.isRunning.collectAsState()
    val ips by HttpService.localNetworkIpsFlow.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton(onBack)
        Spacer(Modifier.height(12.dp))
        BypassSectionCard("局域网 HTTP 工具") {
            BypassSwitchRow(
                "HTTP 服务",
                "只在完整工具构建中可用，供局域网调试和规则检查使用。",
                running,
                onCheckedChange = { enabled -> if (enabled) HttpService.start() else HttpService.stop() },
            )
            if (ips.isNotEmpty()) BypassMutedText("地址：${ips.joinToString()}", 12)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("网络能力") { BypassMutedText("远程订阅、在线帮助和更新检查在完整工具构建的高级规则页面中提供。默认离线构建不会注册 HTTP 服务，也不包含网络权限。", 12) }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("在线工具") {
            BypassNavRow("远程订阅与规则更新", onOpenSubscriptions)
            Spacer(Modifier.height(8.dp))
            BypassNavRow("在线帮助", onOpenHelp)
            Spacer(Modifier.height(8.dp))
            BypassModeButton("检查应用更新", false, onCheckUpdate)
        }
    }
}

@Composable
fun BypassAboutPage(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton(onBack); Spacer(Modifier.height(12.dp))
        BypassSectionCard("Bypass Ads") { Text("0.1.0", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink); Spacer(Modifier.height(8.dp)); BypassMutedText("完全离线的广告自动跳过工具。", 13) }
    }
}

@Composable
fun BypassLicensesPage(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton(onBack); Spacer(Modifier.height(12.dp))
        BypassSectionCard("GPL-3.0 开源许可") { BypassMutedText("本应用基于 GKD（gkd-kit）构建，遵循 GPL-3.0 许可。\n\n源码与许可信息见项目仓库 LICENSE 文件。\n\n第三方广告规则仅用于本地个人自用构建，未获授权公开再分发。", 12, 20) }
    }
}

@Composable private fun BypassBackButton(onBack: () -> Unit) = BypassModeButton("返回", false, onBack)
@Composable private fun BypassStateLine(label: String, healthy: Boolean) = Text("${if (healthy) "●" else "○"} $label", fontSize = 13.sp, color = if (healthy) BypassPalette.Accent else BypassPalette.Muted)
private fun android.content.Context.openAppDetails() = openBypassSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
private fun android.content.Context.openNotificationSettings() = openBypassSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
private fun android.content.Context.openBatterySettings() = openBypassSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
private fun android.content.Context.openBypassSettings(intent: Intent) { runCatching { startActivity(intent) }.getOrElse { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } } }
