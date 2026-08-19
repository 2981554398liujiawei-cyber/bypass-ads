package li.songe.gkd.bypass

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import li.songe.gkd.util.appIconMapFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBypassTime(epochMs: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
private fun Drawable.toBypassImage() = remember(this) { toBitmap().asImageBitmap() }

@Composable
fun BypassHomePage(engine: BypassEngine) {
    val context = LocalContext.current
    val state by engine.serviceState.collectAsState()
    val master by engine.masterEnabled.collectAsState()
    val stats by engine.stats.collectAsState()
    val fallback by engine.genericFallbackEnabled.collectAsState()
    val skipped by engine.skipCount.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassStatusCard(state.label, state.description, state.status == BypassServiceStatus.NORMAL, if (state.status == BypassServiceStatus.NORMAL) null else "开启无障碍") {
            engine.requestServiceRecovery()
            context.openBypassSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("自动跳过开屏广告") {
            BypassSwitchRow("自动跳过开屏广告", if (master) "已开启" else "已暂停", master, engine::setMasterEnabled)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("保护范围") {
            Row {
                BypassMetric("有专用规则的应用", "${stats.appCount} 个应用", Modifier.weight(1f))
                Spacer(Modifier.size(10.dp))
                BypassMetric("专用规则组", "${stats.groupCount}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            BypassMutedText("规则总数：${stats.ruleCount} · 通用保护：${if (fallback) "已开启" else "已关闭"}", 13)
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("跳过统计") { BypassMetric("本次安装以来", "$skipped 次") }
    }
}

@Composable
fun BypassAppsPage(engine: BypassEngine) {
    var apps by remember { mutableStateOf(emptyList<BypassAppInfo>()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { apps = engine.getProtectedApps().sortedBy { it.appName }; loading = false }
    val scope = rememberCoroutineScope()
    val icons by appIconMapFlow.collectAsState()
    if (loading) return BypassMutedText("正在加载应用列表…")
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BypassMutedText("关闭某个应用后，Bypass Ads 将不再对该应用执行开屏规则。", 12)
            Spacer(Modifier.height(8.dp))
        }
        items(apps, key = { it.packageName }) { entry ->
            val icon = icons[entry.packageName]?.toBypassImage()
            BypassAppRow(entry.appName, entry.packageName, icon, entry.enabled) { enabled ->
                scope.launch { engine.setAppEnabled(entry.packageName, enabled) }
                apps = apps.map { if (it.packageName == entry.packageName) it.copy(enabled = enabled) else it }
            }
        }
    }
}

@Composable
fun BypassRecentPage(engine: BypassEngine) {
    val recent by engine.recentActions.collectAsState()
    val scope = rememberCoroutineScope()
    if (recent.isEmpty()) return BypassMutedText("还没有跳过记录。打开带开屏广告的 App 后，这里会显示记录。")
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BypassModeButton("清除记录", false, onClick = { scope.launch { engine.clearRecentActions() } })
            Spacer(Modifier.height(12.dp))
        }
        items(recent, key = { it.time }) { action ->
            Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(18.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(action.appName ?: action.appId, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
                    Text(action.groupName?.let { "已跳过 · $it" } ?: "已跳过开屏广告", Modifier.padding(top = 3.dp), fontSize = 12.sp, color = BypassPalette.Muted)
                }
                Text(formatBypassTime(action.time), fontSize = 12.sp, color = BypassPalette.Faint)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun BypassSettingsPage(onOpenService: () -> Unit, onOpenRules: () -> Unit, onOpenAdvanced: () -> Unit, onOpenRecent: () -> Unit, onOpenAbout: () -> Unit, onOpenLicenses: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassNavRow("服务与权限", onOpenService)
        Spacer(Modifier.height(10.dp)); BypassNavRow("规则库", onOpenRules)
        Spacer(Modifier.height(10.dp)); BypassNavRow("高级设置", onOpenAdvanced)
        Spacer(Modifier.height(10.dp)); BypassNavRow("记录管理", onOpenRecent)
        Spacer(Modifier.height(10.dp)); BypassNavRow("关于", onOpenAbout)
        Spacer(Modifier.height(10.dp)); BypassNavRow("开源许可", onOpenLicenses)
    }
}

@Composable
fun BypassServicePermissionsPage(engine: BypassEngine, onBack: () -> Unit) {
    val context = LocalContext.current
    val service by engine.serviceState.collectAsState()
    val permissions by engine.permissionState.collectAsState()
    LaunchedEffect(Unit) { engine.refreshPermissionState() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton(onBack); Spacer(Modifier.height(12.dp))
        BypassSectionCard("无障碍服务") {
            BypassStateLine(service.label, service.status == BypassServiceStatus.NORMAL); Spacer(Modifier.height(10.dp))
            BypassModeButton(if (service.status == BypassServiceStatus.NEED_AUTHORIZATION) "开启无障碍" else "管理无障碍服务", false, onClick = {
                engine.requestServiceRecovery(); context.openBypassSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("通知权限") {
            BypassStateLine(if (permissions.notificationGranted) "已允许" else "未允许", permissions.notificationGranted); Spacer(Modifier.height(10.dp))
            BypassModeButton("管理", false, onClick = { context.openNotificationSettings() })
        }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("电池优化") {
            BypassStateLine(if (permissions.ignoringBatteryOptimizations) "未受限制" else "可能限制后台运行", permissions.ignoringBatteryOptimizations); Spacer(Modifier.height(10.dp))
            BypassModeButton("去设置", false, onClick = { context.openBatterySettings() })
        }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("后台运行") {
            BypassMutedText("可在系统应用详情中允许后台运行，并检查通知设置。", 12); Spacer(Modifier.height(10.dp))
            BypassModeButton("系统设置", false, onClick = { context.openAppDetails() })
        }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("服务恢复") {
            BypassMutedText("已授权但暂时掉线时，Bypass Ads 会尝试恢复服务。", 12); Spacer(Modifier.height(10.dp))
            BypassModeButton("立即检查", false, onClick = { engine.requestServiceRecovery(); engine.refreshPermissionState() })
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
        BypassBackButton(onBack); Spacer(Modifier.height(12.dp))
        BypassSectionCard("跳过策略") {
            BypassSwitchRow("通用开屏保护", "仅匹配可点击的标准「跳过」按钮。", fallback, engine::setGenericFallbackEnabled)
            Spacer(Modifier.height(6.dp)); BypassSwitchRow("跳过成功提示", if (toastEnabled) "显示 ✨Bypass Ads✨" else "静默跳过", toastEnabled, engine::setActionToastEnabled)
        }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("服务自动恢复") {
            BypassMutedText("服务授权存在但实例掉线时，会使用本机恢复能力重新检查。", 12); Spacer(Modifier.height(10.dp))
            BypassModeButton("立即检查", false, onClick = engine::requestServiceRecovery)
        }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("后台运行优化") {
            BypassMutedText("关闭电池优化有助于系统在后台保持服务。", 12); Spacer(Modifier.height(10.dp))
            BypassModeButton("管理", false, onClick = { context.openBatterySettings() })
        }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("规则与记录") {
            BypassModeButton("恢复内置规则", false, onClick = { scope.launch { engine.restoreBundledRules() } }); Spacer(Modifier.height(8.dp))
            BypassModeButton("清空触发记录", false, onClick = { scope.launch { engine.clearRecentActions() } })
        }
    }
}

@Composable
fun BypassRulesPage(engine: BypassEngine, onOpenApps: () -> Unit, onOpenDetail: () -> Unit, onOpenAdvanced: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val metadata by engine.ruleMetadata.collectAsState()
    val fallback by engine.genericFallbackEnabled.collectAsState()
    var result by remember { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val filename = uri.lastPathSegment?.substringAfterLast('/')
        result = if (text == null) "无法读取所选文件" else engine.importLocalRules(text, filename).message
    } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassSectionCard("当前规则包") {
            Text("Bypass Ads 开屏规则", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink); Spacer(Modifier.height(6.dp))
            BypassStateLine("正在使用", true); Spacer(Modifier.height(10.dp))
            BypassMutedText("来源：${if (metadata.sourceType == BypassRuleSourceType.BUNDLED) "内置规则" else "本地导入"}\n规则版本：${metadata.bundleVersion?.toString() ?: "未标记版本"}\n覆盖应用：${metadata.appCount}\n专用规则组：${metadata.groupCount}\n规则：${metadata.ruleCount}\n更新时间：${metadata.installedAt.takeIf { it > 0 }?.let(::formatBypassTime) ?: "尚未加载"}", 13, 20)
            metadata.sourceFileName?.let { BypassMutedText("文件：$it", 12) }; Spacer(Modifier.height(10.dp))
            BypassModeButton("查看规则包详情", false, onOpenDetail)
        }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("规则能力") {
            BypassMutedText("专用开屏规则 · ${metadata.appCount} 个应用", 13); Spacer(Modifier.height(6.dp))
            BypassMutedText("通用开屏规则 · ${if (fallback) "已启用" else "已关闭"}", 13); Spacer(Modifier.height(6.dp))
            BypassMutedText("微信小程序 · 已包含\n支付宝小程序 · 已包含", 13, 20); Spacer(Modifier.height(10.dp))
            BypassModeButton("高级设置", false, onOpenAdvanced)
        }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("规则管理") {
            BypassModeButton("查看覆盖应用", false, onOpenApps); Spacer(Modifier.height(8.dp))
            BypassModeButton("导入本地规则", false, onClick = { choose.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }); Spacer(Modifier.height(8.dp))
            BypassModeButton("恢复内置规则", false, onClick = { scope.launch { result = engine.restoreBundledRules().message } }); Spacer(Modifier.height(10.dp))
            BypassMutedText("只接受 JSON / JSON5 中的开屏广告规则；外部通用规则和其它类别不会导入。", 12)
            result?.let { Text(it, Modifier.padding(top = 10.dp), fontSize = 12.sp, color = BypassPalette.Muted) }
        }
    }
}

@Composable
fun BypassRuleDetailPage(engine: BypassEngine, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(emptyList<BypassAppInfo>()) }
    val metadata by engine.ruleMetadata.collectAsState()
    LaunchedEffect(metadata.sha256) { apps = engine.getProtectedApps().sortedBy { it.appName } }
    val filtered = apps.filter { it.appName.contains(query, true) || it.packageName.contains(query, true) }
    Column(Modifier.fillMaxSize()) {
        BypassBackButton(onBack); Spacer(Modifier.height(12.dp))
        BypassMutedText("版本 ${metadata.bundleVersion ?: "未标记"} · ${metadata.appCount} 个应用 · ${metadata.ruleCount} 条规则", 12); Spacer(Modifier.height(10.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索已保护应用") }, singleLine = true); Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.packageName }) { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
fun BypassAboutPage(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton(onBack); Spacer(Modifier.height(12.dp))
        BypassSectionCard("Bypass Ads") { Text("0.1.0", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink); Spacer(Modifier.height(8.dp)); BypassMutedText("完全离线的开屏广告自动跳过工具。", 13) }
    }
}

@Composable
fun BypassLicensesPage(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassBackButton(onBack); Spacer(Modifier.height(12.dp))
        BypassSectionCard("GPL-3.0 开源许可") { BypassMutedText("本应用基于 GKD（gkd-kit）构建，遵循 GPL-3.0 许可。\n\n源码与许可信息见项目仓库 LICENSE 文件。\n\n第三方开屏规则仅用于本地个人自用构建，未获授权公开再分发。", 12, 20) }
    }
}

@Composable private fun BypassBackButton(onBack: () -> Unit) = BypassModeButton("返回", false, onBack)
@Composable private fun BypassStateLine(label: String, healthy: Boolean) = Text("${if (healthy) "●" else "○"} $label", fontSize = 13.sp, color = if (healthy) BypassPalette.Accent else BypassPalette.Muted)

private fun android.content.Context.openAppDetails() = openBypassSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
private fun android.content.Context.openNotificationSettings() = openBypassSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
private fun android.content.Context.openBatterySettings() = openBypassSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
private fun android.content.Context.openBypassSettings(intent: Intent) {
    runCatching { startActivity(intent) }.getOrElse { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
}
