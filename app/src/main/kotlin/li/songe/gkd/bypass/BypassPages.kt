package li.songe.gkd.bypass

import android.content.Intent
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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import li.songe.gkd.util.appIconMapFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBypassTime(epochMs: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
fun BypassHomePage(engine: BypassEngine) {
    val context = LocalContext.current; val state by engine.serviceState.collectAsState()
    val master by engine.masterEnabled.collectAsState(); val stats by engine.stats.collectAsState(); val fallback by engine.genericFallbackEnabled.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassStatusCard(state.label, state.description, state.status == BypassServiceStatus.NORMAL, if (state.status == BypassServiceStatus.NORMAL) null else "开启无障碍") {
            engine.requestServiceRecovery(); context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("自动跳过开屏广告") { BypassSwitchRow("开屏保护", if (master) "已开启" else "已暂停", master, engine::setMasterEnabled) }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("保护范围") { Row { BypassMetric("有专用规则的应用", "${stats.appCount} 个应用", Modifier.weight(1f)); Spacer(Modifier.size(10.dp)); BypassMetric("专用规则组", "${stats.groupCount}", Modifier.weight(1f)) } }
        Spacer(Modifier.height(14.dp))
        BypassSectionCard("通用开屏保护") { BypassSwitchRow("通用开屏保护", if (fallback) "已开启：尝试跳过标准的「跳过」按钮" else "已关闭：仅使用专用规则", fallback, engine::setGenericFallbackEnabled) }
    }
}

@Composable
fun BypassAppsPage(engine: BypassEngine) {
    var apps by remember { mutableStateOf(emptyList<BypassAppInfo>()) }; var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { apps = engine.getProtectedApps().sortedBy { it.appName }; loading = false }
    val scope = rememberCoroutineScope(); val icons by appIconMapFlow.collectAsState()
    if (loading) return BypassMutedText("正在加载应用列表…")
    LazyColumn(Modifier.fillMaxSize()) {
        item { BypassMutedText("关闭某个应用后，Bypass Ads 将不再对该应用执行规则。", 12); Spacer(Modifier.height(8.dp)) }
        items(apps, key = { it.packageName }) { entry ->
            BypassAppRow(entry.appName, entry.packageName, null, entry.enabled) { enabled ->
                scope.launch { engine.setAppEnabled(entry.packageName, enabled) }; apps = apps.map { if (it.packageName == entry.packageName) it.copy(enabled = enabled) else it }
            }
        }
    }
}

@Composable
fun BypassRecentPage(engine: BypassEngine) {
    val recent by engine.recentActions.collectAsState(); val scope = rememberCoroutineScope()
    if (recent.isEmpty()) return BypassMutedText("还没有跳过记录。打开带开屏广告的 App 后，这里会显示记录。")
    LazyColumn(Modifier.fillMaxSize()) {
        item { BypassModeButton("清除记录", false, onClick = { scope.launch { engine.clearRecentActions() } }); Spacer(Modifier.height(12.dp)) }
        items(recent, key = { it.time }) { action ->
            Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(18.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(action.appName ?: action.appId, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink); Text(action.groupName?.let { "已跳过 · $it" } ?: "已跳过开屏广告", Modifier.padding(top = 3.dp), fontSize = 12.sp, color = BypassPalette.Muted) }
                Text(formatBypassTime(action.time), fontSize = 12.sp, color = BypassPalette.Faint)
            }; Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun BypassSettingsPage(engine: BypassEngine, onOpenLicenses: () -> Unit, onOpenRules: () -> Unit) {
    val context = LocalContext.current; val state by engine.serviceState.collectAsState(); val fallback by engine.genericFallbackEnabled.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassSectionCard("服务与权限") { BypassMutedText("无障碍服务 · ${state.label}", 13); Spacer(Modifier.height(10.dp)); BypassModeButton(if (state.status == BypassServiceStatus.NORMAL) "管理无障碍服务" else "开启无障碍", false, onClick = { engine.requestServiceRecovery(); context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }); Spacer(Modifier.height(10.dp)); BypassMutedText("后台运行与通知权限可在系统设置中检查。", 12) }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("规则库") { BypassMutedText("管理内置开屏规则与本地导入。", 13); Spacer(Modifier.height(10.dp)); BypassModeButton("打开规则库", false, onOpenRules) }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("高级设置") { BypassSwitchRow("通用开屏保护", "仅匹配可点击的标准「跳过」按钮。", fallback, engine::setGenericFallbackEnabled) }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("关于") { BypassMutedText("Bypass Ads ${li.songe.gkd.META.versionName}", 13); Spacer(Modifier.height(4.dp)); BypassMutedText("完全离线的开屏广告自动跳过工具。", 12); Spacer(Modifier.height(10.dp)); BypassModeButton("开源许可", false, onOpenLicenses) }
    }
}

@Composable
fun BypassRulesPage(engine: BypassEngine) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val stats by engine.stats.collectAsState(); val fallback by engine.genericFallbackEnabled.collectAsState(); var result by remember { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch { val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull(); result = if (text == null) "无法读取所选文件" else engine.importLocalRules(text).message } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BypassSectionCard("内置开屏规则") { BypassMutedText("已启用 · ${stats.appCount} 个应用 · ${stats.groupCount} 个专用规则组 · ${stats.ruleCount} 条规则", 13) }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("通用开屏保护") { BypassSwitchRow("通用开屏保护", if (fallback) "已开启" else "已关闭", fallback, engine::setGenericFallbackEnabled) }
        Spacer(Modifier.height(14.dp)); BypassSectionCard("本地规则包") { BypassMutedText("只接受 JSON / JSON5 中的开屏广告规则；其它规则会被忽略。", 12); Spacer(Modifier.height(10.dp)); BypassModeButton("导入本地规则", false, onClick = { choose.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }); Spacer(Modifier.height(8.dp)); BypassModeButton("恢复内置规则", false, onClick = { scope.launch { result = engine.restoreBundledRules().message } }); result?.let { Text(it, Modifier.padding(top = 10.dp), fontSize = 12.sp, color = BypassPalette.Muted) } }
    }
}

@Composable
fun BypassLicensesPage(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { BypassModeButton("返回", false, onBack); Spacer(Modifier.height(12.dp)); BypassSectionCard("GPL-3.0 开源许可") { BypassMutedText("本应用基于 GKD（gkd-kit）构建，遵循 GPL-3.0 许可。\n\n源码与许可信息见项目仓库 LICENSE 文件。\n\n第三方开屏规则仅用于本地个人自用构建，未获授权公开再分发。", 12, 20) } }
}
